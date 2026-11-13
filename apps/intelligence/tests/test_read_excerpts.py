"""The transport proof and later seed projection must use the identical bounded text."""

from auralisy_intelligence.providers.excerpts import excerpt


def test_excerpt_is_byte_bounded_and_idempotent_at_whitespace_boundaries():
    for text in (
        "guitar " * 1000,
        "钢琴 鼓点 " * 1000,
        "first\n\n" + "rhythm " * 1000,
        "a\n" + "vocals\n" * 1000,
    ):
        for budget in (1, 7, 31, 3500):
            value = excerpt(text, budget)
            assert len(value.encode("utf-8")) <= budget
            assert excerpt(value, budget) == value
