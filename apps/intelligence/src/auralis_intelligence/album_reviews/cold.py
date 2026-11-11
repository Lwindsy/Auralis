"""Request-local live discovery. No file paths, saved URLs, excerpts or cross-request cache."""

import asyncio
import time
import unicodedata
from urllib.parse import unquote, urlsplit

from auralis_intelligence.agents import CallDenied

from .evidence_quality import page_rejection


class ColdEvidence:
    cold = True
    albums = {}  # Explicitly empty; never used to provide candidate identities.

    def __init__(self, *, overlap=False, sources_per_album=2, race=False):
        self.overlap = overlap
        self.race = race
        self.sources_per_album = sources_per_album
        self.urls, self.reads = {}, {}
        self.events = []
        self.failed_ids = set()
        self.keep_background = False
        self.background = {}

    def sources(self, identifier):
        if identifier in self.reads and not self.reads[identifier]:
            return []
        return self.urls.get(identifier, [])

    def take_reads(self, identifier):
        return self.reads.pop(identifier, None)

    async def take_background_reads(self, identifier):
        state = self.background.get(identifier)
        if state is None:
            return []
        reads = []
        try:
            await state["done"].wait()
            reads = list(state["extra"])
            state["extra"].clear()
            state["release"].set()
            await state["task"]
            return reads
        except asyncio.CancelledError:
            for read in reads:
                read.clear()
            state["task"].cancel()
            await asyncio.gather(state["task"], return_exceptions=True)
            raise
        finally:
            self.background.pop(identifier, None)

    async def discard_unselected(self, selected):
        for identifier, state in list(self.background.items()):
            if identifier not in selected:
                state["task"].cancel()
                await asyncio.gather(state["task"], return_exceptions=True)
                self.background.pop(identifier, None)

    async def prepare(self, execution, candidates, tools, *, lane="FOREGROUND"):
        origin = time.perf_counter()

        def normalize(text):
            return "".join(c for c in unicodedata.normalize("NFKD", text.casefold()) if c.isalnum())

        async def gather_owned(coroutines):
            tasks = [asyncio.create_task(coro) for coro in coroutines]
            try:
                return await asyncio.gather(*tasks)
            finally:
                for task in tasks:
                    if not task.done():
                        task.cancel()
                await asyncio.gather(*tasks, return_exceptions=True)

        async def read_sources(candidate, selected, parallel):
            identifier, identity = candidate["release_group_id"], candidate["identity"]

            async def read(source):
                began = time.perf_counter()
                try:
                    result = await tools.read(
                        execution,
                        candidate_id=identifier,
                        url=source["url"],
                        title=identity["title"],
                        artist=identity["artist_credit"],
                        purpose="SCREEN_RESEARCH",
                    )
                except asyncio.CancelledError:
                    self.events.append(
                        {
                            "kind": "read",
                            "candidate_id": identifier,
                            "url": source["url"],
                            "start_ms": round((began - origin) * 1000),
                            "end_ms": round((time.perf_counter() - origin) * 1000),
                            "status": "CANCELLED",
                        }
                    )
                    raise
                usable = result.receipt["status"] == "READ_OK"
                rejection = page_rejection(result.text, identity) if usable else "READ_FAILED"
                usable = usable and rejection is None
                self.events.append(
                    {
                        "kind": "read",
                        "candidate_id": identifier,
                        "url": source["url"],
                        "start_ms": round((began - origin) * 1000),
                        "end_ms": round((time.perf_counter() - origin) * 1000),
                        "status": result.receipt["status"],
                        "usable": usable,
                        "technical_rejection": rejection,
                        "failure_reason": result.receipt["failure_reason"],
                    }
                )
                if not usable:
                    result.clear()
                    return None
                return result

            if parallel and self.race and self.sources_per_album == 1 and self.keep_background:
                first = asyncio.get_running_loop().create_future()
                state = {"done": asyncio.Event(), "release": asyncio.Event(), "extra": []}
                self.background[identifier] = state

                async def collect():
                    tasks = [asyncio.create_task(read(source)) for source in selected]
                    owned = []
                    chosen = None
                    try:
                        for completed in asyncio.as_completed(tasks):
                            result = await completed
                            if result is None:
                                continue
                            owned.append(result)
                            if chosen is None:
                                chosen = result
                                self.reads[identifier] = [result]
                                if not first.done():
                                    first.set_result(None)
                            else:
                                state["extra"].append(result)
                        if chosen is None:
                            self.reads[identifier] = []
                            if not first.done():
                                first.set_result(None)
                        state["done"].set()
                        await state["release"].wait()
                    finally:
                        for task in tasks:
                            if not task.done():
                                task.cancel()
                        outcomes = await asyncio.gather(*tasks, return_exceptions=True)
                        for result in outcomes:
                            if (
                                hasattr(result, "clear")
                                and result is not chosen
                                and result not in owned
                            ):
                                result.clear()
                        for result in state["extra"]:
                            result.clear()
                        state["extra"].clear()
                        if chosen is not None and chosen in self.reads.get(identifier, []):
                            chosen.clear()
                        state["done"].set()
                        if not first.done():
                            self.reads[identifier] = []
                            first.set_result(None)

                state["task"] = execution.spawn(collect)
                await first
            elif parallel and self.race and self.sources_per_album == 1:
                tasks = [asyncio.create_task(read(source)) for source in selected]
                chosen = None
                try:
                    for task in asyncio.as_completed(tasks):
                        result = await task
                        if result is not None:
                            chosen = result
                            break
                finally:
                    for task in tasks:
                        if not task.done():
                            task.cancel()
                    outcomes = await asyncio.gather(*tasks, return_exceptions=True)
                    for outcome in outcomes:
                        if outcome is not chosen and hasattr(outcome, "clear"):
                            outcome.clear()
                self.reads[identifier] = [chosen] if chosen is not None else []
            elif parallel:
                # Batch only the number still needed; failures reuse this search's remaining links.
                accepted = []
                queue = list(selected)
                while queue and len(accepted) < self.sources_per_album:
                    batch = queue[: self.sources_per_album - len(accepted)]
                    queue = queue[len(batch) :]
                    accepted.extend(
                        r
                        for r in await gather_owned(read(source) for source in batch)
                        if r is not None
                    )
                self.reads[identifier] = accepted
            else:
                self.reads[identifier] = [
                    r for source in selected if (r := await read(source)) is not None
                ]

        async def discover(candidate):
            identifier, identity = candidate["release_group_id"], candidate["identity"]
            started = time.perf_counter()
            try:
                hits = await tools.search(
                    execution,
                    candidate_id=identifier,
                    lane=lane,
                    query=f'{identity["artist_credit"]} "{identity["title"]}" album review music sound',
                )
            except CallDenied as error:
                self.failed_ids.add(identifier)
                self.events.append(
                    {
                        "kind": "search",
                        "candidate_id": identifier,
                        "start_ms": round((started - origin) * 1000),
                        "end_ms": round((time.perf_counter() - origin) * 1000),
                        "error": str(error),
                    }
                )
                return
            # Respect the search result order, with complementary publishers selected before reads.
            selected, publishers = [], set()
            if self.overlap:
                title_key = normalize(identity["title"])
                artist_key = normalize(identity["artist_credit"])
                hits = sorted(
                    hits,
                    key=lambda h: (
                        -(
                            3
                            * bool(
                                title_key and title_key in normalize(h.title + " " + unquote(h.url))
                            )
                            + (artist_key in normalize(h.title + " " + unquote(h.url)))
                        )
                    ),
                )
            for hit in hits:
                host = urlsplit(hit.url).hostname or ""
                publisher = tools.domain(host).top_domain_under_public_suffix or host
                if publisher in publishers:
                    continue
                publishers.add(publisher)
                selected.append({"url": hit.url, "title": hit.title})
                if len(selected) >= (4 if self.overlap else self.sources_per_album):
                    break
            self.urls[identifier] = selected
            self.events.append(
                {
                    "kind": "search",
                    "candidate_id": identifier,
                    "start_ms": round((started - origin) * 1000),
                    "end_ms": round((time.perf_counter() - origin) * 1000),
                    "hits": len(hits),
                    "selected": selected,
                }
            )
            if self.overlap:
                await read_sources(candidate, selected, True)

        await gather_owned(discover(candidate) for candidate in candidates)
        if not self.overlap:
            await gather_owned(
                read_sources(candidate, self.urls.get(candidate["release_group_id"], []), False)
                for candidate in candidates
            )
