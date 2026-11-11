import { afterEach, describe, expect, test } from "vitest";
import { message, stateMessage } from "../../src/api/messages.ts";
import { genreNote } from "../../src/content/genreNotes.ts";
import { NOTES_EN } from "../../src/content/genreNotes.en.ts";
import en from "../../src/i18n/en.ts";
import { copy, i18n, LOCALES, withLanguage } from "../../src/i18n/index.ts";
import zhCN from "../../src/i18n/zh-CN.ts";
import { emptyInput } from "../../src/state/input.ts";
import { ready } from "../fixtures.ts";

afterEach(() => {
  i18n.set("zh-CN");
  i18n.chosen = false;
});

/** Every key path with the kind of value behind it (functions with their arity). */
function shape(value: unknown, path = ""): string[] {
  if (typeof value === "function") return [`${path}:fn${value.length}`];
  if (Array.isArray(value)) return [`${path}:array${value.length}`];
  if (value && typeof value === "object")
    return Object.keys(value)
      .sort()
      .flatMap((key) =>
        shape((value as Record<string, unknown>)[key], `${path}.${key}`),
      );
  return [`${path}:${value === null ? "null" : typeof value}`];
}

/** Every string a dictionary can show, with interpolations filled by placeholders. */
function strings(value: unknown): string[] {
  if (typeof value === "string") return [value];
  if (typeof value === "function") {
    const args = Array.from({ length: value.length }, (_, i) =>
      i === 0 ? ["x", "y"] : i,
    );
    return [String((value as (...a: unknown[]) => unknown)(...args))];
  }
  if (value && typeof value === "object")
    return Object.values(value).flatMap(strings);
  return [];
}

describe("interface language", () => {
  test("the English dictionary covers exactly the Chinese keys", () => {
    expect(shape(en)).toEqual(shape(zhCN));
  });

  test("English copy contains no Chinese characters", () => {
    const leaks = strings(en).filter((text) => /\p{Script=Han}/u.test(text));
    expect(leaks).toEqual([]);
  });

  test("switch labels are 中文 and EN, the ids are the request languages", () => {
    expect(LOCALES.map((item) => item.label)).toEqual(["中文", "EN"]);
    expect(LOCALES.map((item) => item.id)).toEqual(["zh-CN", "en"]);
  });

  test("messages follow the current language and still never echo server text", () => {
    expect(message("QUEUE_FULL")).toBe("当前等待人数较多，请稍后重试。");
    i18n.set("en");
    expect(message("QUEUE_FULL")).toBe(
      "Lots of people in line right now. Try again in a bit.",
    );
    expect(message("SOME <b>server</b> TEXT")).toBe(en.genericFailure);
    const state = ready(2, 3);
    expect(stateMessage(state)).toBe(en.states.PARTIAL);
  });

  test("a new round carries the language shown when it is asked for", () => {
    const input = { ...emptyInput(), request_text: "rainy day jazz" };
    expect(withLanguage(input).language).toBe("zh-CN");
    i18n.set("en");
    const sent = withLanguage(input);
    expect(sent.language).toBe("en");
    expect(sent.request_text).toBe("rainy day jazz");
    expect(input.language).toBe("zh-CN");
  });

  test("a restored English discovery opens in English unless a language was picked", () => {
    i18n.adopt("en");
    expect(i18n.locale).toBe("en");
    expect(copy().deck.more).toBe("Hit me with more");
    i18n.set("zh-CN");
    i18n.adopt("en");
    expect(i18n.locale).toBe("zh-CN");
    i18n.adopt("fr");
    expect(i18n.locale).toBe("zh-CN");
  });

  test("every genre note has an English version", () => {
    i18n.set("en");
    expect(genreNote("Trip-Hop")).toBe(NOTES_EN["trip hop"]);
    expect(genreNote("R&B")).toBe(NOTES_EN["r and b"]);
    expect(genreNote("Zeuhl")).toBeNull();
    expect(genreNote("Jazz", "zh-CN")).toMatch(/\p{Script=Han}/u);
    for (const [key, note] of Object.entries(NOTES_EN)) {
      expect(genreNote(key, "zh-CN"), key).not.toBeNull();
      expect(note, key).not.toMatch(/\p{Script=Han}/u);
    }
  });
});
