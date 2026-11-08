"""Request-local live discovery. No file paths, saved URLs, excerpts or cross-request cache."""

import asyncio
import time
from urllib.parse import urlsplit, unquote
import unicodedata
import re

from auralis_intelligence.agents import CallDenied


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

    def sources(self, identifier):
        if identifier in self.reads and not self.reads[identifier]:
            return []
        return self.urls.get(identifier, [])

    def take_reads(self, identifier):
        return self.reads.pop(identifier, None)

    async def prepare(self, execution, candidates, tools):
        origin = time.perf_counter()
        def normalize(text):
            return ''.join(c for c in unicodedata.normalize('NFKD', text.casefold()) if c.isalnum())
        async def gather_owned(coroutines):
            tasks = [asyncio.create_task(coro) for coro in coroutines]
            try:
                return await asyncio.gather(*tasks)
            finally:
                for task in tasks:
                    if not task.done():task.cancel()
                await asyncio.gather(*tasks, return_exceptions=True)
        async def read_sources(candidate, selected, parallel):
            identifier, identity = candidate['release_group_id'], candidate['identity']
            async def read(source):
                began=time.perf_counter()
                try:
                    result=await tools.read(execution,candidate_id=identifier,url=source['url'],
                        title=identity['title'],artist=identity['artist_credit'],purpose='SCREEN_RESEARCH')
                except asyncio.CancelledError:
                    self.events.append({'kind':'read','candidate_id':identifier,'url':source['url'],
                        'start_ms':round((began-origin)*1000),'end_ms':round((time.perf_counter()-origin)*1000),'status':'CANCELLED'})
                    raise
                usable = result.receipt['status']=='READ_OK'
                if self.overlap and usable:
                    body = normalize(result.text)
                    artist_tokens = [normalize(t) for t in re.split(r'\W+', identity['artist_credit']) if len(t)>=3 and t.casefold() not in {'the','and'}]
                    title_key = normalize(identity['title'])
                    title_present = title_key in body if title_key else any(line.strip() == identity['title'] for line in result.text.splitlines())
                    usable = len(result.text)>=300 and title_present and (normalize(identity['artist_credit']) in body or any(t in body for t in artist_tokens))
                self.events.append({'kind':'read','candidate_id':identifier,'url':source['url'],
                    'start_ms':round((began-origin)*1000),'end_ms':round((time.perf_counter()-origin)*1000),
                    'status':result.receipt['status'],'usable':usable,'failure_reason':result.receipt['failure_reason']})
                if not usable:
                    result.clear()
                    return None
                return result
            if parallel and self.race and self.sources_per_album == 1:
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
                        if not task.done():task.cancel()
                    outcomes = await asyncio.gather(*tasks, return_exceptions=True)
                    for outcome in outcomes:
                        if outcome is not chosen and hasattr(outcome, 'clear'):outcome.clear()
                self.reads[identifier] = [chosen] if chosen is not None else []
            elif parallel:
                # Batch only the number still needed; failures reuse this search's remaining links.
                accepted = []
                queue = list(selected)
                while queue and len(accepted)<self.sources_per_album:
                    batch = queue[:self.sources_per_album-len(accepted)]
                    queue = queue[len(batch):]
                    accepted.extend(r for r in await gather_owned(read(source) for source in batch) if r is not None)
                self.reads[identifier] = accepted
            else:
                self.reads[identifier] = [r for source in selected if (r := await read(source)) is not None]
        async def discover(candidate):
            identifier, identity = candidate['release_group_id'], candidate['identity']
            started = time.perf_counter()
            try:
                hits = await tools.search(execution, candidate_id=identifier,
                    query=f'{identity["artist_credit"]} "{identity["title"]}" album review music sound')
            except CallDenied as error:
                self.failed_ids.add(identifier)
                self.events.append({'kind':'search','candidate_id':identifier,'start_ms':round((started-origin)*1000),
                                    'end_ms':round((time.perf_counter()-origin)*1000),'error':str(error)})
                return
            # Respect the search result order, with complementary publishers selected before reads.
            selected, publishers = [], set()
            if self.overlap:
                title_key = normalize(identity['title'])
                artist_key = normalize(identity['artist_credit'])
                hits = sorted(hits, key=lambda h: -(3*bool(title_key and title_key in normalize(h.title+' '+unquote(h.url))) + (artist_key in normalize(h.title+' '+unquote(h.url)))))
            for hit in hits:
                host = urlsplit(hit.url).hostname or ''
                publisher = tools.domain(host).top_domain_under_public_suffix or host
                if publisher in publishers:
                    continue
                publishers.add(publisher)
                selected.append({'url':hit.url,'title':hit.title})
                if len(selected) >= (4 if self.overlap else self.sources_per_album):
                    break
            self.urls[identifier] = selected
            self.events.append({'kind':'search','candidate_id':identifier,'start_ms':round((started-origin)*1000),
                                'end_ms':round((time.perf_counter()-origin)*1000),'hits':len(hits),'selected':selected})
            if self.overlap:
                await read_sources(candidate, selected, True)
        await gather_owned(discover(candidate) for candidate in candidates)
        if not self.overlap:
            await gather_owned(read_sources(candidate,self.urls.get(candidate['release_group_id'],[]),False) for candidate in candidates)
