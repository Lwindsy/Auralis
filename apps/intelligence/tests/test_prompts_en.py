"""English prompt editions: complete, well-formed and not yet wired into the runtime."""

import json
import re
from importlib.resources import files

import pytest
from auralis_intelligence.album_reviews import batch, prompts_en, research, seed_context, writing
from auralis_intelligence.preferences import prompts as prompts_zh
from auralis_intelligence.preferences import prompts_en as preferences_en
from auralis_intelligence.ranking import prompts_en as ranking_en
from auralis_intelligence.ranking import selection

HAN = re.compile(r"[一-鿿]")
PAIRS = [
    ("auralis_intelligence.album_reviews", "batch.v6"),
    ("auralis_intelligence.album_reviews", "research.v2"),
    ("auralis_intelligence.album_reviews", "writing.v1.2"),
    ("auralis_intelligence.ranking", "select.v1"),
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


def test_runtime_still_sends_the_chinese_prompts():
    assert batch.PROMPT == text("auralis_intelligence.album_reviews", "batch.v6.txt")
    assert research.RESEARCH_PROMPT == text("auralis_intelligence.album_reviews", "research.v2.txt")
    assert writing.WRITING_PROMPT == text("auralis_intelligence.album_reviews", "writing.v1.2.txt")
    assert selection.PROMPT == text("auralis_intelligence.ranking", "select.v1.txt")
    assert HAN.search(seed_context.SEED_INSTRUCTION)


def test_english_prompts_name_english_output_and_word_lengths():
    album = "auralis_intelligence.album_reviews"
    assert "English cards" in text(album, "batch.v6.en.txt")
    assert "about 40 words" in text(album, "batch.v6.en.txt")
    assert "original English reviews" in text(album, "writing.v1.2.en.txt")
    assert "50 to 110 words" in text(album, "writing.v1.2.en.txt")
    assert "{measured} words" in prompts_en.LONG_LENGTH_NOTE
    assert prompts_en.LONG_LENGTH_NOTE.format(measured=12).startswith(
        "The previous long review was only about 12 words"
    )


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
    ids: set[str] = set()
    for section in prompt.split("\n## Example ")[1:]:
        blocks = json_blocks(section)
        given = [block for block in blocks if "request_text" in block]
        if given:
            request = given[0]["request_text"]
            ids = {row["id"] for row in given[0].get("branches", given[0].get("genres", []))}
        else:
            # An example may reuse the previous input with new words given in prose.
            request = re.search(r'`request_text` to "(.+?)"', section).group(1)
        for block in blocks:
            if "request_text" in block:
                continue
            rows = block.get("branches", []) + block.get("preferences", [])
            rows += block.get("constraints", []) + block.get("soft_preferences", [])
            for row in rows:
                assert row["quote"] in request, row
                if "id" in row:
                    assert row["id"] in ids, row
                checked += 1
            for limit in block.get("unresolved_constraints", []):
                assert limit in request
                checked += 1
    assert checked >= 6
