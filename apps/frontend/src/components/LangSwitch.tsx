import type { CSSProperties } from "react";
import { i18n, LOCALES, useCopy, type Locale } from "../i18n/index.ts";
import { withViewTransition } from "../lib/motion.ts";
import { ripple } from "./motion/Contours.tsx";

/**
 * Switches the page language in place. The pill's thumb springs across to the chosen
 * label, the landscape ripples out from the switch, and the whole page cross-fades from
 * one language to the other (a View Transition, so text that changes length re-flows
 * under the fade instead of jumping). Reviews already written stay as they were
 * returned; only the page's own copy changes.
 *
 * Two pressable buttons rather than a radio group: arrow keys stay with the listening
 * room (previous / next album) even while the switch has focus.
 */
export function LangSwitch() {
  const words = useCopy();
  const current = i18n.locale;
  const index = Math.max(
    0,
    LOCALES.findIndex((item) => item.id === current),
  );

  const choose = (locale: Locale, from: HTMLElement) => {
    if (locale === i18n.locale) return;
    const box = from.getBoundingClientRect();
    ripple(1.2, box.left + box.width / 2, box.top + box.height / 2);
    withViewTransition(() => i18n.set(locale), "language");
  };

  return (
    <div
      className="lang"
      role="group"
      aria-label={words.app.language}
      style={{ "--at": index } as CSSProperties}
    >
      <span className="lang__thumb" aria-hidden="true" />
      {LOCALES.map((item) => (
        <button
          key={item.id}
          type="button"
          lang={item.id}
          aria-label={item.name}
          aria-pressed={item.id === current}
          className="lang__option"
          onClick={(event) => choose(item.id, event.currentTarget)}
        >
          {item.label}
        </button>
      ))}
    </div>
  );
}
