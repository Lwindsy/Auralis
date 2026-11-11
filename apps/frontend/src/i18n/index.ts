import type { DiscoveryInput } from "../api/types.ts";
import { useStore } from "../state/hooks.ts";
import en from "./en.ts";
import zhCN, { type Copy } from "./zh-CN.ts";

export type { Copy } from "./zh-CN.ts";

/** Interface languages; the value doubles as the `language` sent with a new round. */
export type Locale = "zh-CN" | "en";

/** Switch labels stay in their own language, whichever one is showing. */
export const LOCALES: readonly { id: Locale; label: string; name: string }[] = [
  { id: "zh-CN", label: "中文", name: "中文" },
  { id: "en", label: "EN", name: "English" },
];

const DICTIONARIES: Record<Locale, Copy> = { "zh-CN": zhCN, en };

export const isLocale = (value: unknown): value is Locale =>
  value === "zh-CN" || value === "en";

/**
 * The interface language of this page. Kept in memory only, like everything else the page
 * holds: nothing goes to browser storage. Changing it re-renders every subscribed view,
 * names the document's language and retitles the tab.
 */
class LocaleStore {
  locale: Locale = "zh-CN";
  /** True once the person has picked a language on this page. */
  chosen = false;
  private adopted = false;
  version = 0;
  private listeners = new Set<() => void>();

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  getVersion = () => this.version;

  set(locale: Locale, chosen = true) {
    this.chosen ||= chosen;
    if (locale === this.locale) return;
    this.locale = locale;
    this.version++;
    applyDocument(locale);
    for (const listener of this.listeners) listener();
  }

  /**
   * A restored discovery that was asked for in English opens in English, unless the
   * person has already picked a language here.
   */
  adopt(language: string | null | undefined) {
    if (this.chosen || this.adopted || !isLocale(language)) return;
    this.adopted = true;
    this.set(language, false);
  }
}

function applyDocument(locale: Locale) {
  if (typeof document === "undefined") return;
  const words = DICTIONARIES[locale].meta;
  document.documentElement.lang = locale;
  document.title = words.title;
  document
    .querySelector<HTMLMetaElement>('meta[name="description"]')
    ?.setAttribute("content", words.description);
}

export const i18n = new LocaleStore();

/** The dictionary for the current language; for code outside React render. */
export const copy = (): Copy => DICTIONARIES[i18n.locale];

/** The dictionary for the current language; re-renders the caller when it changes. */
export function useCopy(): Copy {
  useStore(i18n);
  return copy();
}

/** A new round always carries the language the page is showing when it is asked for. */
export const withLanguage = (input: DiscoveryInput): DiscoveryInput => ({
  ...structuredClone(input),
  language: i18n.locale,
});
