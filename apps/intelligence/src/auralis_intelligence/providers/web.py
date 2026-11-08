"""Authorized web tools with actual read receipts and connection-time DNS checks."""

import asyncio
import ipaddress
import re
import socket
from dataclasses import dataclass
from datetime import datetime, timezone
from hashlib import sha256
from typing import Protocol
from urllib.parse import urljoin, urlsplit, urlunsplit
from uuid import uuid4

import aiohttp
import tldextract
from auralis_intelligence.agents import CallDenied, Execution
from auralis_intelligence.contracts import digest, validate_definition
from bs4 import BeautifulSoup

from .excerpts import excerpt
from .gateway import ModelGateway
from .types import ReadResult, SearchAdapter, SearchHit


class FetchFailure(RuntimeError):
    """Safe failure code with no page content or request credentials."""


def checked_url(value: str) -> str:
    try:
        parts = urlsplit(value)
        host = parts.hostname
        if (
            parts.scheme != "https"
            or not host
            or parts.username is not None
            or parts.password is not None
            or parts.port not in (None, 443)
        ):
            raise ValueError
        host = host.encode("idna").decode("ascii").lower().rstrip(".")
        if (
            any(character.isspace() or ord(character) < 32 for character in value)
            or "\\" in value
            or "%" in host
        ):
            raise ValueError
        try:
            ipaddress.ip_address(host)
        except ValueError:
            pass
        else:
            raise FetchFailure("IP_LITERAL_BLOCKED")
        if re.fullmatch(r"(?:0x[0-9a-f]+|[0-9]+)(?:\.(?:0x[0-9a-f]+|[0-9]+))*", host):
            raise FetchFailure("IP_LITERAL_BLOCKED")
        if "." not in host:
            raise FetchFailure("HOST_NOT_PUBLIC")
        return urlunsplit(("https", host, parts.path or "/", parts.query, ""))
    except (ValueError, UnicodeError) as error:
        raise FetchFailure("URL_NOT_ALLOWED") from error


class PublicResolver(aiohttp.abc.AbstractResolver):
    """Validate the exact addresses handed to the connector, avoiding a second DNS lookup."""

    def __init__(self, delegate: aiohttp.abc.AbstractResolver | None = None) -> None:
        self.delegate = delegate or aiohttp.resolver.ThreadedResolver()

    async def resolve(self, host: str, port: int = 0, family: int = socket.AF_UNSPEC):
        addresses = await self.delegate.resolve(host, port, family)
        if not addresses or any(
            not ipaddress.ip_address(item["host"]).is_global for item in addresses
        ):
            raise FetchFailure("PRIVATE_NETWORK_BLOCKED")
        return addresses

    async def close(self) -> None:
        await self.delegate.close()


@dataclass(frozen=True)
class FetchResponse:
    status: int
    headers: dict[str, str]
    body: bytes
    retrieval_mode: str = "NETWORK"
    observed_at: str | None = None


class PageFetcher(Protocol):
    async def fetch(self, url: str, max_bytes: int, timeout_seconds: float) -> FetchResponse: ...


class AiohttpPageFetcher:
    async def fetch(self, url: str, max_bytes: int, timeout_seconds: float) -> FetchResponse:
        resolver = PublicResolver()
        try:
            connector = aiohttp.TCPConnector(
                resolver=resolver, use_dns_cache=False, force_close=True
            )
            async with aiohttp.ClientSession(
                connector=connector,
                trust_env=False,
                auto_decompress=False,
                cookie_jar=aiohttp.DummyCookieJar(),
                timeout=aiohttp.ClientTimeout(total=timeout_seconds),
                headers={"User-Agent": "Auralis/0.1 review reader", "Accept-Encoding": "identity"},
            ) as session:
                async with session.get(checked_url(url), allow_redirects=False) as response:
                    headers = {key.lower(): value for key, value in response.headers.items()}
                    if headers.get("content-encoding", "identity").lower() not in ("identity", ""):
                        raise FetchFailure("UNSUPPORTED_CONTENT_ENCODING")
                    if response.content_length is not None and response.content_length > max_bytes:
                        raise FetchFailure("BODY_TOO_LARGE")
                    body = bytearray()
                    async for chunk in response.content.iter_chunked(min(max_bytes + 1, 16384)):
                        if len(body) + len(chunk) > max_bytes:
                            raise FetchFailure("BODY_TOO_LARGE")
                        body.extend(chunk)
                    return FetchResponse(response.status, headers, bytes(body))
        except (aiohttp.ClientError, OSError) as error:
            raise FetchFailure("READ_CONNECTION_FAILED") from error
        finally:
            await resolver.close()


def extract_text(body: bytes, content_type: str) -> str:
    media_type = content_type.split(";", 1)[0].strip().lower()
    if media_type == "text/plain":
        return body.decode("utf-8", errors="replace").strip()
    if media_type not in {"text/html", "application/xhtml+xml"}:
        raise FetchFailure("UNSUPPORTED_CONTENT_TYPE")
    soup = BeautifulSoup(body, "html.parser")
    for element in soup(
        ["script", "style", "nav", "header", "footer", "aside", "form", "noscript", "svg"]
    ):
        element.decompose()
    main = soup.find("article") or soup.find("main") or soup.body or soup
    return "\n".join(line.strip() for line in main.get_text("\n").splitlines() if line.strip())


class ToolGateway:
    def __init__(
        self,
        fetcher: PageFetcher | None = None,
        *,
        search_adapter: SearchAdapter | None = None,
        model_gateway: ModelGateway | None = None,
    ) -> None:
        self.fetcher = fetcher or AiohttpPageFetcher()
        self.search_adapter = search_adapter
        self.model_gateway = model_gateway
        # Bundled PSL only: domain extraction must not issue hidden network calls.
        self.domain = tldextract.TLDExtract(
            suffix_list_urls=(), cache_dir=None, include_psl_private_domains=True
        )

    async def search(
        self, execution: Execution, *, candidate_id: str, query: str, lane: str = "FOREGROUND"
    ) -> tuple[SearchHit, ...]:
        if (
            "web_search.v1" not in execution.start["capabilities"]
            or self.search_adapter is None
            or self.model_gateway is None
        ):
            raise CallDenied("SEARCH_NOT_CONFIGURED")
        execution.ensure_active()
        allowed, blocked = (), ()
        binding = self.model_gateway.binding(execution, "screen")
        self.search_adapter.check_binding(binding, search=True)
        bound = len(query.encode("utf-8")) + sum(len(domain) for domain in allowed + blocked) + 1024
        if bound > binding.max_input_tokens:
            raise CallDenied("SEARCH_INPUT_TOO_LARGE")
        # Hosted search may add result context beyond the query. Reserve the full
        # approved input bound; the query's own byte bound cannot price that context.
        plan = execution.invocation_plan(
            "SEARCH",
            "screen",
            {"query": query, "domains": allowed, "blocked": blocked},
            candidate_id=candidate_id,
            lane=lane,
            max_input_tokens=binding.max_input_tokens,
            max_output_tokens=min(binding.max_output_tokens, 1024),
        )
        result = await execution.invoke(
            plan,
            lambda: self.search_adapter.search(binding, query, allowed, blocked),
            timeout_seconds=execution.start["agent_policy"]["timing"]["research_timeout_fg_ms"]
            / 1000,
        )
        if result.finish != "COMPLETE":
            raise CallDenied("SEARCH_UNAVAILABLE")
        hits = []
        for hit in result.output:
            try:
                url = checked_url(hit.url)
            except FetchFailure:
                continue
            hits.append(SearchHit(url, hit.title, hit.snippet))
        return tuple(hits)

    async def read(
        self,
        execution: Execution,
        *,
        candidate_id: str,
        url: str,
        title: str,
        artist: str,
        purpose: str = "SCREEN_RESEARCH",
        priority: int = 0,
    ) -> ReadResult:
        if "web_read.v1" not in execution.start["capabilities"]:
            raise CallDenied("READ_NOT_CONFIGURED")
        requested = checked_url(url)
        guards = execution.start["web_guards"]
        read_id = f"read:{uuid4().hex}"
        receipt = {
            "read_id": read_id,
            "candidate_id": candidate_id,
            "purpose": purpose,
            "requested_url": requested,
            "final_url": None,
            "publisher_key": None,
            "status": "READ_FAILED",
            "failure_reason": None,
            "read_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "content_digest": None,
            "text_chars": 0,
            "near_duplicate_of": None,
            "checks": {"mentions_title": False, "mentions_artist": False},
        }

        async def fetch() -> ReadResult:
            current = requested
            try:
                for redirect in range(guards["max_redirects"] + 1):
                    execution.ensure_active()

                    async def get_page() -> FetchResponse:
                        execution.ensure_active()

                        authorization = await execution.control_request(
                            "read-authorization",
                            {
                                "read_id": read_id,
                                "candidate_id": candidate_id,
                                "url": current,
                                "purpose": purpose,
                            },
                        )
                        validate_definition("ReadAuthorizationResult", authorization)
                        if authorization["read_id"] != read_id:
                            raise CallDenied("READ_AUTHORIZATION_MISMATCH")
                        if authorization["decision"] != "GRANTED":
                            raise CallDenied(authorization["reason"] or "READ_ALREADY_CLAIMED")
                        receipt["read_at"] = (
                            datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
                        )
                        with execution.clock.measure("read"):
                            return await self.fetcher.fetch(
                                current, guards["max_bytes"], guards["read_timeout_ms"] / 1000
                            )

                    read_priority = priority if priority else (0 if execution.foreground else 4)
                    read_priority = min(
                        read_priority, execution.priorities.get(candidate_id, read_priority)
                    )
                    response = await execution.scheduler.run(
                        "read",
                        get_page,
                        priority=read_priority,
                        host=urlsplit(current).hostname,
                        key=candidate_id,
                    )
                    if response.status in {301, 302, 303, 307, 308}:
                        if (
                            redirect == guards["max_redirects"]
                            or "location" not in response.headers
                        ):
                            raise FetchFailure("REDIRECT_LIMIT")
                        current = checked_url(urljoin(current, response.headers["location"]))
                        continue
                    if response.status != 200:
                        raise FetchFailure("READ_HTTP_ERROR")
                    if len(response.body) > guards["max_bytes"]:
                        raise FetchFailure("BODY_TOO_LARGE")
                    text = (
                        await execution.scheduler.cpu(
                            extract_text, response.body, response.headers.get("content-type", "")
                        )
                        if hasattr(execution.scheduler, "cpu")
                        else extract_text(response.body, response.headers.get("content-type", ""))
                    )
                    if not text:
                        raise FetchFailure("EMPTY_BODY")

                    host = urlsplit(current).hostname
                    publisher = self.domain(host).top_domain_under_public_suffix or host
                    receipt.update(
                        final_url=current,
                        publisher_key=publisher,
                        status="READ_OK",
                        content_digest="sha256:" + sha256(text.encode("utf-8")).hexdigest(),
                        text_chars=len(text),
                        excerpt_digest=digest(excerpt(text, 3500)),
                        checks={
                            "mentions_title": title.casefold() in text.casefold(),
                            "mentions_artist": artist.casefold() in text.casefold(),
                        },
                    )
                    receipt["retrieval_mode"] = response.retrieval_mode
                    if response.observed_at is not None:
                        receipt["read_at"] = response.observed_at
                    registered = await execution.control_request(
                        "read-receipts", {"items": [receipt]}
                    )
                    validate_definition("ReadRegistrationResult", registered)
                    if read_id not in registered["accepted_read_ids"]:
                        raise CallDenied("READ_NOT_PERMITTED")
                    return ReadResult(receipt, text)
                raise FetchFailure("REDIRECT_LIMIT")
            except CallDenied as error:
                receipt.update(status="BLOCKED_BY_CONTROL", failure_reason=str(error))
            except (FetchFailure, asyncio.TimeoutError, TimeoutError, OSError) as error:
                receipt.update(
                    status="READ_FAILED",
                    failure_reason=str(error)
                    if isinstance(error, FetchFailure)
                    else "READ_TIMEOUT_OR_CONNECTION",
                )
            return ReadResult(receipt)

        async def bounded() -> ReadResult:
            try:
                return await asyncio.wait_for(fetch(), guards["read_timeout_ms"] / 1000)
            except (asyncio.TimeoutError, TimeoutError):
                receipt.update(status="READ_FAILED", failure_reason="READ_TIMEOUT")
                return ReadResult(receipt)

        return await bounded()
