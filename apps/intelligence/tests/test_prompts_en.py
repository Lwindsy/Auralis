"""English prompt structure and runtime selection."""

import asyncio
import json
import re
from dataclasses import replace
from importlib.resources import files

import pytest
from auralisy_intelligence.agents.demo import DemoHarness, _reply
from auralisy_intelligence.album_reviews import prompts_en, research, seed_context, writing
from auralisy_intelligence.contracts import content_digest
from auralisy_intelligence.preferences import prompts as prompts_zh
from auralisy_intelligence.preferences import prompts_en as preferences_en
from auralisy_intelligence.ranking import prompts_en as ranking_en
from auralisy_intelligence.ranking import selection

HAN = re.compile(r"[一-鿿]")
PAIRS = [
    ("auralisy_intelligence.album_reviews", "batch.v7"),
    ("auralisy_intelligence.album_reviews", "research.v2"),
    ("auralisy_intelligence.album_reviews", "writing.v1.4"),
    ("auralisy_intelligence.ranking", "select.v1"),
]


def text(package: str, name: str) -> str:
    return files(package).joinpath(name).read_text(encoding="utf-8")


@pytest.mark.parametrize(("package", "stem"), PAIRS)
def test_english_edition_keeps_the_paragraph_structure(package, stem):
    chinese, english = text(package, f"{stem}.txt"), text(package, f"{stem}.en.txt")
    assert HAN.search(chinese)
    assert not HAN.search(english)
    assert english.count("\n") == chinese.count("\n")
    assert english.endswith("\n") and "\r" not in english


@pytest.mark.parametrize("english", [False, True])
def test_recommendation_uses_one_language_for_every_model_stage(tmp_path, english):
    async def run():
        harness = DemoHarness(scenario="text", r=3, k=2, n=1, root=tmp_path)
        try:
            start = harness.request
            if english:
                start["input"]["language"] = "en"
                raw = start["input"]["raw"]
                raw.update(language="en", request_text="Quiet jazz for late evenings")
                raw["input_digest"] = content_digest(raw, "input_digest")
                start["payload_digest"] = content_digest(start)
            edition = preferences_en if english else prompts_zh
            systems = {
                "genre_pass1_v0_9": edition.PASS_ONE_PROMPT,
                "genre_pass2_v1_0": edition.PASS_TWO_PROMPT,
                "research_v2": research.RESEARCH_PROMPT_EN if english else research.RESEARCH_PROMPT,
                "select_v1": selection.PROMPT_EN if english else selection.PROMPT,
                "short_review_v1_4": writing.WRITING_PROMPT_EN
                if english
                else writing.WRITING_PROMPT,
                "long_review_v1_6": (
                    writing.WRITING_PROMPT_EN if english else writing.WRITING_PROMPT
                )
                + writing.LONG_RESEARCH_INSTRUCTION,
            }
            seen = set()

            def reply(binding, request):
                name = request.schema_name.removesuffix("_en")
                assert request.schema_name == name + ("_en" if english else "")
                assert request.system == systems[name]
                seen.add(name)
                answer = _reply(binding, replace(request, schema_name=name))
                if english and name.startswith("short_review"):
                    answer.output["text"] = (
                        "Quiet drums and warm piano leave room around the melody, while the guitar stays low in the mix [R1]. "
                        "The close vocal and slowly changing harmonies give the record a calm feel, with enough movement to hold your attention through a late evening listen [R2]."
                    )
                if english and name.startswith("long_review"):
                    answer.output["sections"] = {
                        "production": "The piano and drums leave space around each phrase [R1].",
                        "instrumentation": "",
                        "vocals": "",
                        "mood": "Soft percussion and gradual harmonies create a quiet atmosphere [R2].",
                    }
                return answer

            harness.adapter.replies = reply
            result = await harness.run()
            assert result["first_outcome"] == "DISPLAYABLE", result
            assert result["published_count"] == 1 and result["long_reviews"] == 2
            assert seen == systems.keys()
        finally:
            harness.close()

    asyncio.run(run())


def test_english_prompts_name_english_output_and_word_lengths():
    album = "auralisy_intelligence.album_reviews"
    assert "English cards" in text(album, "batch.v7.en.txt")
    assert "about 40 words" in text(album, "batch.v7.en.txt")
    assert "original English reviews" in text(album, "writing.v1.4.en.txt")
    assert "50 to 110 words" in text(album, "writing.v1.4.en.txt")
    assert "350 to 600 English words" in text(album, "writing.v1.4.en.txt")
    assert "400至800个汉字" in text(album, "writing.v1.4.txt")
    assert "{measured} words" in prompts_en.LONG_LENGTH_NOTE
    assert prompts_en.LONG_LENGTH_NOTE.format(measured=12).startswith(
        "The previous long review contained about 12 words"
    )


REVIEW_PROMPTS = [
    ("batch.v7.txt", "batch.v7.en.txt"),
    ("writing.v1.4.txt", "writing.v1.4.en.txt"),
]


@pytest.mark.parametrize(("chinese_name", "english_name"), REVIEW_PROMPTS)
def test_review_prompts_forbid_defensive_writing(chinese_name, english_name):
    album = "auralisy_intelligence.album_reviews"
    chinese, english = text(album, chinese_name), text(album, english_name)
    # The rule is stated outright, with the phrasings the user rejected named as banned.
    assert "严禁防御性写作" in chinese and "No defensive writing, ever." in english
    for banned in ("可能不适合你", "不是已证实的某风格", "邻近尝试", "资料将它描述为", "评论形容"):
        assert banned in chinese
    for banned in (
        "may not be for you",
        "not a proven/confirmed [genre]",
        "rather than X, expect Y",
    ):
        assert banned in english
    assert "自信" in chinese and "confident" in english
    # The old instructions that asked for drawbacks, gaps and kept uncertainty are gone.
    for old in (
        "可能劝退之处",
        "可能劝退的地方",
        "可能不喜欢什么",
        "与用户目标的偏离，以及缺少哪些目标特点的依据",
        "保留其不确定性",
        "资料未知就说明未知",
        "不一概改成赞美",
    ):
        assert old not in chinese
    for old in (
        "what might put someone off",
        "what might put them off",
        "what they might not like",
        "how it departs from the user's target, and which target traits lack support",
        "Keep the uncertainty",
        "say it is unknown",
        "rather than turning them all into praise",
    ):
        assert old not in english


def test_seed_instruction_shares_appeal_without_gaps():
    assert "可能劝退" not in seed_context.SEED_INSTRUCTION
    assert "不写候选与种子的差距" in seed_context.SEED_INSTRUCTION
    assert "put the user off" not in prompts_en.SEED_INSTRUCTION
    assert "never the gaps between candidate and seed" in prompts_en.SEED_INSTRUCTION
    assert seed_context.SeedContext.version("batch.v7") == "batch.v7.seed.v2"


def test_inline_fragments_have_english_editions():
    seed_lines = [line for line in prompts_en.SEED_INSTRUCTION.splitlines() if line]
    chinese_lines = [line for line in seed_context.SEED_INSTRUCTION.splitlines() if line]
    assert len(seed_lines) == len(chinese_lines) == 8
    assert prompts_en.SEED_INSTRUCTION.startswith("\n")
    assert prompts_en.SEED_INSTRUCTION.endswith("\n")
    for value in (
        prompts_en.SEED_INSTRUCTION,
        prompts_en.BATCH_REPAIR,
        prompts_en.LONG_LENGTH_NOTE,
        prompts_en.WRITE_REPAIR,
        prompts_en.COMPLETE_REQUEST_DESCRIPTION,
        ranking_en.SELECT_REPAIR_INSTRUCTION,
    ):
        assert value.strip() and not HAN.search(value)
    assert prompts_en.BATCH_REPAIR.endswith("Error: ")


def headings(prompt: str) -> list[str]:
    return [line.split(" ", 1)[0] for line in prompt.splitlines() if line.startswith("#")]


def json_blocks(prompt: str) -> list[dict]:
    return [json.loads(block) for block in re.findall(r"```json\n(.*?)\n```", prompt, re.S)]


@pytest.mark.parametrize(
    ("chinese", "english"),
    [
        (prompts_zh.PASS_ONE_PROMPT, preferences_en.PASS_ONE_PROMPT),
        (prompts_zh.PASS_TWO_PROMPT, preferences_en.PASS_TWO_PROMPT),
    ],
    ids=["pass1", "pass2"],
)
def test_preference_prompts_keep_sections_tables_and_examples(chinese, english):
    assert headings(english) == headings(chinese)
    assert english.count("\n|") == chinese.count("\n|")
    assert english.count("\n- ") == chinese.count("\n- ")
    assert len(json_blocks(english)) == len(json_blocks(chinese))
    # Only translated-name aliases in the example data may stay in Chinese.
    without_aliases = re.sub(r'"aliases":\[[^\]]*\]', "", english)
    assert not HAN.search(without_aliases)


@pytest.mark.parametrize(
    "prompt",
    [preferences_en.PASS_ONE_PROMPT, preferences_en.PASS_TWO_PROMPT],
    ids=["pass1", "pass2"],
)
def test_example_quotes_are_exact_spans_of_the_example_text(prompt):
    checked = 0
    numbers: set[int] = set()
    for section in prompt.split("\n## Example ")[1:]:
        blocks = json_blocks(section)
        given = [block for block in blocks if "request_text" in block]
        if given:
            request = given[0]["request_text"]
            numbers = {
                row["number"] for row in given[0].get("branches", given[0].get("genres", []))
            }
        else:
            # An example may reuse the previous input with new words given in prose.
            request = re.search(r'`request_text` to "(.+?)"', section).group(1)
        for block in blocks:
            if "request_text" in block:
                continue
            rows = block.get("branches", []) + block.get("preferences", [])
            rows += block.get("constraints", []) + block.get("named_genres", [])
            rows += block.get("soft_preferences", [])
            for row in rows:
                assert row["quote"] in request, row
                if "number" in row:
                    assert type(row["number"]) is int and row["number"] in numbers, row
                checked += 1
            for limit in block.get("unresolved_constraints", []):
                assert limit in request
                checked += 1
    assert checked >= 6
