"""Google HTML search adapter; consent rejection is explicit, failures never fake hits."""

import asyncio
from urllib.parse import parse_qs, urlsplit

import httpx
from bs4 import BeautifulSoup

from auralis_intelligence.agents.ports import CallDenied, CallResult, Usage
from .types import SearchHit


class GoogleSearchAdapter:
    def __init__(self, *, transport=None):
        self.client = httpx.AsyncClient(transport=transport, timeout=25, follow_redirects=True,
                                       headers={"User-Agent": "Mozilla/5.0"})
        self.lock = asyncio.Lock()

    def check_binding(self, binding, *, search=False):
        pass  # No model is called or charged by Google HTML search.

    async def close(self):
        await self.client.aclose()

    async def search(self, binding, query, allowed_domains=(), blocked_domains=()):
        async with self.lock:
            response = await self.client.get("https://www.google.com/search", params={"q": query, "hl": "en", "gbv": "1"})
            if response.url.host == "consent.google.com":
                soup = BeautifulSoup(response.text, "html.parser")
                form = next((f for f in soup.select("form") if f.select_one('input[name="set_eom"][value="true"]')), None)
                if form is None:
                    raise CallDenied("GOOGLE_CONSENT_UNAVAILABLE")
                data = {x["name"]: x.get("value", "") for x in form.select("input[name]")}
                response = await self.client.post("https://consent.google.com/save", data=data)
        if response.status_code != 200 or response.url.host != "www.google.com" or "/sorry" in response.url.path:
            raise CallDenied("SEARCH_UNAVAILABLE")
        soup = BeautifulSoup(response.text, "html.parser")
        hits, seen = [], set()
        for anchor in soup.select("a"):
            heading = anchor.find("h3")
            if heading is None:
                continue
            url = anchor.get("href", "")
            if url.startswith("/url?"):
                values = parse_qs(urlsplit(url).query)
                url = (values.get("q") or values.get("url") or [""])[0]
            host = urlsplit(url).hostname or ""
            if not url.startswith("https://") or url in seen or any(host == d or host.endswith("." + d) for d in blocked_domains):
                continue
            if allowed_domains and not any(host == d or host.endswith("." + d) for d in allowed_domains):
                continue
            seen.add(url)
            parent = anchor.parent.parent if anchor.parent else anchor
            hits.append(SearchHit(url, heading.get_text(" ", strip=True), parent.get_text(" ", strip=True)[:1200]))
        if not hits and ("enablejs" in response.text or "unusual traffic" in response.text.lower()):
            raise CallDenied("GOOGLE_RENDER_REQUIRED")
        return CallResult(tuple(hits[:15]), usage=Usage(0, 0, 0))
