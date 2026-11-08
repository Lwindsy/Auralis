"""Explicitly synthetic standalone exercise of the real stage composition."""

from dataclasses import asdict
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import tempfile
from typing import Any

from auralis_intelligence.album_reviews import ResearchStage, WriteStage
from auralis_intelligence.contracts import content_digest, digest
from auralis_intelligence.preferences import PreferencesStage, ReferenceCatalog, merge_preferences
from auralis_intelligence.providers import ModelBinding, ModelGateway, ToolGateway
from auralis_intelligence.providers.testing import ScriptedModelAdapter, StaticSearchAdapter
from auralis_intelligence.providers.web import FetchResponse
from auralis_intelligence.ranking import SelectStage
from auralis_intelligence.recall import WeightConfig
from .control_double import JavaControlDouble
from .pipeline import FirstBatchPipeline
from .ports import CallResult, Usage
from .recall_stage import RecallStage
from .runtime import RoundSupervisor


def _reply(binding, request):
    payload = request.input
    name = request.schema_name
    if name.startswith("genre_pass1"):
        output = {"branches": [{"id": "branch-jazz", "quote": payload["request_text"]}]}
    elif name.startswith("genre_pass2"):
        output = {"preferences": [{"id": "jazz", "kind": "prefer", "basis": "explicit", "quote": payload["request_text"]}], "constraints": [], "unresolved_constraints": [],
                  "soft_preferences": [{"description": "希望安静、适合深夜独处的音乐", "kind": "context", "basis": "explicit", "quote": payload["request_text"]}]}
    elif name == "research_v2" and not payload["sources"]:
        output = {"action": "read", "query": "", "urls": [x["url"] for x in payload["available_sources"]], "reason": "Read independently discovered synthetic sources", "assessment": None}
    elif name in {"screen_v1", "research_v2"}:
        refs = [item["ref"] for item in payload["sources"]]
        output = {"reads": [{"ref": ref, "about_target": "yes", "substantive": "yes"} for ref in refs], "score": 4,
                  "matches": [{"pref": payload["preferences"][0]["ref"], "refs": refs, "note": "合成资料中的克制编配与当前听感需求相关"}], "tensions": [], "unknowns": ["真实音乐效果未测"], "summary": "合成资料讨论克制编配、空间感与演奏细节，未提供真实音乐质量证据。"}
    elif name.startswith("short_review_v1"):
        output = {"text": "这张专辑在合成测试资料中呈现克制的编配，钢琴与轻缓鼓点之间留有清晰空间，旋律以渐进的方式展开而不过度依赖厚重音墙[R1]。另一份资料强调人声与吉他的亲近感；如果这次想在深夜安静地听，可以留意这种舒缓而细致的安排，这里只验证程序流程而不代表真实试听评价[R2]。", "refs": ["R1", "R2"]}
    elif name.startswith("long_review_v1"):
        paragraph = "这是明确的合成长评材料，用于验证程序篇幅、引用和并行交付，不能当作实际专辑的音乐评价。资料描述演奏间保留的空间、声音缓慢推进时的变化，以及不同评论对这种处理的分歧。我们仍需真实模型和实际网页测试，才能判断最后写出的乐评是否自然准确。"
        output = {"status": "ok", "sections": {key: paragraph * 4 + ("[R1]" if index % 2 == 0 else "[R2]") for index, key in enumerate(("production", "instrumentation", "vocals", "mood"))}, "refs": ["R1", "R2"], "missing": [], "tool_requests": []}
    elif name == "select_v1":
        output = {"order": [{"c": item["c"], "verdict": "recommend" if index < payload["target_count"] else "reserve", "note": "仅为合成排列，用于验证完整候选与交付"} for index, item in enumerate(payload["candidates"])]}
    else:
        raise ValueError("UNKNOWN_DEMO_PROMPT")
    if name == "research_v2" and "action" not in output:
        output = {"action": "finish", "query": "", "urls": [], "reason": "Evidence sufficient", "assessment": output}
    return CallResult(output, usage=Usage(10, 10, 0))


class DemoPages:
    async def fetch(self, url, max_bytes, timeout_seconds):
        identifier = url.rstrip("/").split("/")[-1]
        title = "Synthetic Album " + identifier
        if "one.example" in url:
            paragraph = "The piano and drums leave space around each phrase. Room echoes shape a restrained texture, and the guitar remains quiet. The critic finds the development slow while describing the production and arrangement in detail. "
        else:
            paragraph = "A close vocal perspective rests above warm organ tones. Soft percussion and gradually changing harmonies create an intimate atmosphere. This separate analysis examines the singer's phrasing, instrumental balance and melodic development. "
        return FetchResponse(200, {"content-type": "text/plain"}, (title + " by Synthetic Artist\n" + paragraph * 3).encode())


class DemoHarness:
    def __init__(self, *, scenario="mixed", r=50, k=15, n=3, root: Path | None = None) -> None:
        if not 1 <= n <= k <= r <= 100:
            raise ValueError("DEMO_COUNTS_REQUIRE_1_LE_N_LE_K_LE_R_LE_100")
        self.temp_root = (root or Path.cwd() / "tmp").resolve()
        self.temp_root.mkdir(parents=True, exist_ok=True)
        album_ids = [f"album-{index}" for index in range(max(24, r + 1))]
        data = {"format_version": 1, "dataset_id": "synthetic-agent-demo-v1", "window": "synthetic",
                "album_ids": album_ids, "tag_ids": ["jazz"], "tag_parents": [], "album_tags": [[1] for _ in album_ids],
                "interactions": [[1 + (index + listener) % 4 for index in range(len(album_ids))] for listener in range(6)]}
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".json", prefix="agent-demo-", dir=self.temp_root, delete=False) as stream:
            json.dump(data, stream)
            self.snapshot = Path(stream.name)
        self.reference = ReferenceCatalog({"branches": [{"id": "branch-jazz", "name": "爵士", "aliases": ["Jazz"], "description": "用于离线流程验证的爵士分支说明。", "genre_ids": ["jazz"]}], "genres": [{"id": "jazz", "name": "爵士", "aliases": ["Jazz"], "description": "用于合成测试的即兴、节奏和配器说明，不代表每张作品事实。"}]})
        self.supervisor = RoundSupervisor(boot_id="synthetic-python")
        self.control = JavaControlDouble("synthetic-java", catalog={identifier: {"title": "Synthetic Album " + identifier, "artist_credit": "Synthetic Artist", "first_release_year": 2000, "release_type": "Album", "primary_genres": [{"genre_id": "jazz", "name": "爵士"}]} for identifier in album_ids}, price_per_token=0)
        self.control.known_review_refs = {identifier: [{"read_id": f"locator-{publisher}-{identifier}", "url": f"https://{publisher}.example/{identifier}", "publisher_key": f"{publisher}.example"} for publisher in ("one", "two")] for identifier in album_ids}
        self.control.preference_resolver = lambda raw, proposal: merge_preferences(raw, proposal, reference=self.reference, seed_descriptions={identifier: "Synthetic Artist — Synthetic Album " + identifier for identifier in album_ids})
        self.bindings = {role: ModelBinding(role, "synthetic-v1", "synthetic-model", "synthetic-zero-price", 180000, 4096, web_search=role == "screen") for role in ("interpret", "screen", "select", "write")}
        self.adapter = ScriptedModelAdapter(_reply)
        self.model = ModelGateway(self.adapter, self.bindings)
        self.tools = ToolGateway(DemoPages(), search_adapter=StaticSearchAdapter(()), model_gateway=self.model)
        self.request = self.make_request(scenario, r, k, n)

    def make_request(self, scenario, r, k, n):
        text = "想听爵士，安静一点，适合深夜独处。" if scenario in {"mixed", "text"} else ""
        raw = {"liked_genres": [{"genre_id": "jazz", "strength": "normal", "focused": False}] if scenario in {"mixed", "genre"} else [],
               "disliked_genre_ids": ["jazz"] if scenario == "no-positive" else [],
               "liked_albums": [{"release_group_id": "album-0", "external": None, "strength": "special", "focused": False}] if scenario in {"mixed", "album"} else [], "mode": "explore", "request_text": text, "language": "zh"}
        raw["input_digest"] = content_digest(raw, "input_digest")
        policy = {"revision": "synthetic-agent-v1", "timing": {key: 120000 for key in ("shortlist_freeze_ms", "short_review_tech_timeout_ms", "select_tech_timeout_ms", "long_review_timeout_ms", "research_timeout_fg_ms", "research_timeout_bg_ms", "interpret_timeout_ms", "recall_timeout_ms")},
                  "screening": {"score_scale": "ANCHORED_1_5", "shortlist_min_score": 3, "supplement_min_score": 3, "min_qualified_reads": 2, "reads_per_album_initial": 3, "max_reads_per_album": 6, "max_searches_per_album": 2, "min_review_chars": 200, "excerpt_token_cap": 2000},
                  "supply": {"wave_overprovision": 2, "replenish_watermark": 3, "supply_batch_size": 3, "rerun_exclusion_scope": "ALL_PRIOR_EXCEPT_READ_FAILED", "next_round_start": "ON_DEMAND", "max_rounds_per_session": 5},
                  "writing": {"short_min_han": 80, "short_max_han": 180, "long_min_han": 0, "long_section_min_han": 0, "long_extra_max_searches": 1, "long_extra_max_reads": 2, "long_tool_turns": 2},
                  "concurrency": {"model_max_in_flight": 20, "background_model_max_in_flight": 8, "search_max_in_flight": 4, "read_max_in_flight": 8, "read_max_per_host": 2}, "recall": {"weights": asdict(WeightConfig())}}
        policy["digest"] = content_digest(policy, "digest")
        exclusions = [{"release_group_id": "album-0", "reason": "SEED"}] if raw["liked_albums"] else []
        result = {"execution_id": "synthetic-execution", "operation": "DISCOVERY_ROUND", "start_mode": "FRESH", "session_id": "synthetic-session", "round_id": "synthetic-round", "round_seq": 1, "parent_round_id": None,
                  "run_id": "synthetic-run", "run_kind": "INITIAL", "foreground": True, "erasure_handle": "synthetic-erasure", "boot": {"python_boot_id": self.supervisor.boot_id, "coordinator_boot_id": self.control.boot_id},
                  "selection_config": {"revision": f"synthetic-{r}-{k}-{n}", "recall_target_count": r, "shortlist_target_count": k, "recommendation_target_count": n}, "agent_policy": policy,
                  "budget": {"session_budget_scope_id": "synthetic-session-budget", "round_budget_scope_id": "synthetic-round-budget", "limits": {"session_cap_microusd": 300000, "round_cap_microusd": 60000, "lane_caps": {"FOREGROUND": 25000, "BACKGROUND": 35000}, "model_calls": 1000, "search_calls": 100, "read_calls": 1000}, "remaining_at_issue": {"session": 300000, "round": 60000, "FOREGROUND": 25000, "BACKGROUND": 35000}, "structure_repair": {"foreground_per_run": 1, "background_per_round": 1}},
                  "deadlines": {"round_deadline_at": (datetime.now(timezone.utc) + timedelta(minutes=10)).isoformat().replace("+00:00", "Z"), "idle_close_ms": 1000},
                  "model_bindings": {role: binding.wire() for role, binding in self.bindings.items()}, "recall_binding": {"strategy_revision": "A-V2", "data_snapshot_id": "synthetic-agent-demo-v1", "window": "synthetic", "weights_digest": digest(asdict(WeightConfig()))},
                  "source_policy": {"policy_epoch": 1, "valid_until": (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat().replace("+00:00", "Z"), "rules": [{"match": {"domain_suffix": domain}, "decision": "ALLOW", "uses": ["READ", "AI_PROCESS", "STORE_LOCATOR"], "reason": "synthetic-only"} for domain in ("one.example", "two.example")], "default_decision": "DENY", "guards": {"https_only": True, "deny_ip_literals": True, "deny_private_networks": True, "max_redirects": 2, "max_bytes": 20000, "read_timeout_ms": 1000}},
                  "input": {"raw": raw, "accepted_preferences": None, "exclusions": exclusions, "exclusion_digest": digest(exclusions)}, "capabilities": ["web_search.v1", "web_read.v1"], "continuation": None, "curated_candidates": None}
        result["payload_digest"] = content_digest(result)
        return result

    def pipeline(self, start, *, background: bool = False):
        from .background import RoundPipeline
        implementation = RoundPipeline if background else FirstBatchPipeline
        return implementation(PreferencesStage(self.model, self.reference), RecallStage(self.snapshot, binding=start["recall_binding"], weights=WeightConfig(**start["agent_policy"]["recall"]["weights"])),
                                  ResearchStage(self.model, self.tools), WriteStage(self.model, self.tools), SelectStage(self.model))

    async def run(self, *, include_calls: bool = False, background: bool = False):
        self.control.register(self.request)
        _, execution = await self.supervisor.accept(self.request, self.pipeline(self.request, background=background), self.control)
        await self.supervisor.wait(execution.id)
        return self.summary(execution, include_calls=include_calls)

    def summary(self, execution, *, include_calls: bool = False):
        first = next((item["payload"] for item in self.control.deliveries.values() if item["kind"] == "CARD_BATCH" and item["payload"]["batch_kind"] == "FIRST"), None)
        summary = {"synthetic": True, "paid_requests": False, "execution_status": execution.status, "error_code": execution.error_code,
                "handoff": execution.handoff, "first_outcome": first["outcome"] if first else None, "reason_code": first["reason_code"] if first else None,
                "analysis_mode": first["analysis_mode"] if first else None, "selection_config": self.request["selection_config"],
                "first_card_count": len(first["cards"]) if first else 0, "published_count": len(self.control.executions[execution.id]["display"]["shown_candidate_ids"]),
                "long_reviews": sum(item["kind"] == "LONG_REVIEW" for item in self.control.deliveries.values()),
                "model_calls": len(self.adapter.requests),
                "authorized_model_calls": sum(item["plan"]["cost_class"] == "MODEL" for item in self.control.invocations.values()),
                "tool_service_calls": sum(item["plan"]["cost_class"] == "TOOL_SERVICE" for item in self.control.invocations.values()),
                "timing": first["timing"] if first else None, "slot_released": execution.slot_released}
        if include_calls:
            summary["calls"] = [{"invocation_id": identifier, "purpose": item["plan"]["purpose"], "lane": item["plan"]["lane"],
                                  "reserved_microusd": item["reserved"], "settled": item["settled"], "usage": item.get("usage")}
                                 for identifier, item in self.control.invocations.items()]
        if execution.final_report is not None:
            summary["round_report"] = execution.final_report
        return summary

    def close(self):
        self.adapter.clear()
        if self.snapshot.resolve().parent != self.temp_root:
            raise RuntimeError("DEMO_PATH_CHANGED")
        self.snapshot.unlink(missing_ok=True)
