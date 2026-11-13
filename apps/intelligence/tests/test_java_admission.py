"""APP-013 wire evidence; three-execution lifecycle tests live in test_three_executions."""

import asyncio
import json
from copy import deepcopy
from pathlib import Path

import httpx
import pytest
from auralisy_intelligence.agents.http import create_execution_app
from auralisy_intelligence.contracts import content_digest, envelope, validate_message
from auralisy_intelligence.preferences import merge_preferences
from test_agent_runtime import setup, start
from test_first_batch import running
from test_preferences import REFERENCE, proposal, raw


def test_admission_http_lost_response_cleanup_deletion_and_boot_fences():
    async def run():
        request = start()
        supervisor, control = setup(request)
        gate = asyncio.Event()

        async def stage(execution):
            await gate.wait()

        app = create_execution_app(supervisor, control, lambda _: stage, token="test")
        async with running(app) as url, httpx.AsyncClient(base_url=url, trust_env=False) as http:
            http.headers.update(
                {"Authorization": "Bearer test", "X-Auralis-Audience": "intelligence"}
            )
            path = "/internal/v1/executions/" + request["execution_id"]
            query = {
                "python_boot_id": supervisor.boot_id,
                "payload_digest": request["payload_digest"],
                "original_deadline": request["deadlines"]["round_deadline_at"],
            }

            async def lookup(parameters=query):
                response = await http.get(path + "/admission", params=parameters)
                assert response.status_code == 200, response.text
                message = response.json()
                validate_message(message)
                return message["payload"]

            assert (await lookup())["status"] == "NOT_ACCEPTED"
            assert (
                await http.put(path, json=envelope("RoundStart", request, "req"))
            ).status_code == 201
            # Ignore the acceptance body: recovery must query the original ID.
            assert (await lookup())["status"] == "ACTIVE"
            runtime = (await http.get("/internal/v1/runtime")).json()
            validate_message(runtime)
            assert runtime["payload"]["execution_capacity"] == 3
            assert [row["execution_id"] for row in runtime["payload"]["active"]] == [
                request["execution_id"]
            ]
            conflict = await http.get(
                path + "/admission", params={**query, "payload_digest": "sha256:" + "0" * 64}
            )
            assert conflict.status_code == 409
            assert (await lookup({**query, "python_boot_id": "previous-boot"}))[
                "status"
            ] == "UNKNOWN"
            assert (await lookup({**query, "original_deadline": "2000-01-01T00:00:00Z"}))[
                "status"
            ] == "UNKNOWN"
            gate.set()
            await supervisor.wait(request["execution_id"])
            terminal = await lookup()
            assert terminal["status"] == "TERMINAL" and terminal["execution"]["slot_released"]
            erased = await http.put(
                "/internal/v1/erasures/" + request["erasure_handle"],
                json=envelope("Erasure", {"epoch": 1}, "erase"),
            )
            assert erased.status_code == 200, erased.text
            cleared = await lookup()
            assert cleared["status"] == "CLEARED" and cleared["execution"] is None
            late = await http.put(path, json=envelope("RoundStart", request, "late"))
            assert late.status_code == 410
            assert late.json()["payload"]["code"] == "SUBJECT_REVOKED"
            runtime = (await http.get("/internal/v1/runtime")).json()
            assert runtime["payload"]["active"] == []

    asyncio.run(run())


SPAN_CASES = json.loads(
    (Path(__file__).parent / "fixtures/codepoint-cases.json").read_text(encoding="utf-8")
)


@pytest.mark.parametrize("case", SPAN_CASES, ids=lambda row: row["name"])
def test_codepoint_spans_preserve_original_text(case):
    span, text = case["span"], case["text"]
    valid = (
        0 <= span["start"] < span["end"] <= len(text)
        and text[span["start"] : span["end"]] == case["expected"]
    )
    assert valid == case["valid"]


@pytest.mark.parametrize("mapped", [True, False])
def test_external_album_mapping_drives_real_preference_merge(mapped):
    cases = json.loads(
        (Path(__file__).parent / "fixtures/java-integration-cases.json").read_text(encoding="utf-8")
    )
    name = "outside-core-genre-mapping" if mapped else "outside-core-no-positive-signal"
    resolved = next(row["payload"] for row in cases if row["name"] == name)
    album = deepcopy(resolved["input"])
    # Use the same reference genre IDs as the existing frozen preference cases.
    if mapped:
        genre = next(iter(REFERENCE.genres))
        album["external"]["mapped_genre_ids"] = [genre]
    value = raw(albums=[album])
    result = merge_preferences(
        value,
        proposal(value),
        reference=REFERENCE,
        seed_descriptions={album["external"]["mb_release_group_id"]: "Synthetic external album"},
    )
    assert result["seeds"] == []
    assert result["has_positive_signal"] == mapped
    assert [row["id"] for row in result["tags"]] == ([genre] if mapped else [])
    assert result["input_digest"] == content_digest(value, "input_digest")
