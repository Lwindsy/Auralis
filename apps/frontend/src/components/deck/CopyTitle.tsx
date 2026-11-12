import { useEffect, useRef, useState } from "react";
import { useCopy } from "../../i18n/index.ts";

/** How long the tick stays after a copy. */
const DONE_MS = 1600;

/** Clipboard API where the page is secure; the old copy command elsewhere. */
async function writeText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    // Fall through to the copy command.
  }
  const field = document.createElement("textarea");
  field.value = text;
  field.setAttribute("readonly", "");
  field.style.cssText = "position:fixed;top:0;left:0;opacity:0;font-size:16px";
  document.body.append(field);
  field.select();
  field.setSelectionRange(0, text.length);
  let done: boolean;
  try {
    done = document.execCommand("copy");
  } catch {
    done = false;
  }
  field.remove();
  return done;
}

/**
 * Touch screens only (deck.css shows it under a coarse pointer): one tap copies the album
 * name, to paste into a music app's search. A tick answers for a moment.
 */
export function CopyTitle({ title }: { title: string }) {
  const words = useCopy().deck;
  const [done, setDone] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => clearTimeout(timer.current), []);

  return (
    <button
      type="button"
      className="copy-title"
      data-done={done || undefined}
      aria-label={done ? words.copied : words.copyTitle}
      onClick={() => {
        void writeText(title).then((ok) => {
          if (!ok) return;
          clearTimeout(timer.current);
          setDone(true);
          timer.current = setTimeout(() => setDone(false), DONE_MS);
        });
      }}
    >
      <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        {done ? (
          <path d="m5 12.5 4.2 4.2L19 7" />
        ) : (
          <>
            <rect x="8.5" y="8.5" width="11" height="11" rx="2.2" />
            <path d="M15.5 5.6V5.2a1.7 1.7 0 0 0-1.7-1.7H6.2a1.7 1.7 0 0 0-1.7 1.7v7.6a1.7 1.7 0 0 0 1.7 1.7h.4" />
          </>
        )}
      </svg>
      <span aria-hidden="true">{done ? words.copied : words.copyShort}</span>
    </button>
  );
}
