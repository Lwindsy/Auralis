import { useEffect, useId, useRef, useState } from "react";
import { useCopy } from "../i18n/index.ts";
import { Icon } from "./Icon.tsx";

/**
 * The data notice at the foot of the page: four short facts on one quiet line, and the
 * full wording in a small card that opens above it. Escape or a click elsewhere closes it.
 */
export function PrivacyNote() {
  const words = useCopy().app.privacy;
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLElement>(null);
  const cardId = useId();

  useEffect(() => {
    if (!open) return;
    const away = (event: PointerEvent) => {
      if (!root.current?.contains(event.target as Node)) setOpen(false);
    };
    const key = (event: KeyboardEvent) => {
      if (event.key === "Escape") setOpen(false);
    };
    document.addEventListener("pointerdown", away);
    document.addEventListener("keydown", key);
    return () => {
      document.removeEventListener("pointerdown", away);
      document.removeEventListener("keydown", key);
    };
  }, [open]);

  return (
    <footer ref={root} className="privacy" aria-label={words.label}>
      <ul className="privacy__points">
        {words.points.map((point) => (
          <li key={point}>{point}</li>
        ))}
      </ul>
      <button
        type="button"
        className="privacy__more"
        aria-expanded={open}
        aria-controls={cardId}
        onClick={() => setOpen((value) => !value)}
      >
        <Icon name="info" size={13} />
        {words.more}
      </button>
      <p id={cardId} className="privacy__card" hidden={!open}>
        {words.full}
      </p>
    </footer>
  );
}
