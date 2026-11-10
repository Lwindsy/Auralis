/**
 * Line glyphs for the three doors. Every stroke is drawn in on first appearance and
 * reacts when its door opens (see entry.css).
 */
export function Glyph({ kind }: { kind: "text" | "genres" | "albums" }) {
  return (
    <svg
      className={`glyph glyph--${kind}`}
      viewBox="0 0 64 64"
      aria-hidden="true"
      focusable="false"
    >
      {kind === "text" ? (
        <>
          <path pathLength="1" d="M10 22h34" />
          <path pathLength="1" d="M10 32h44" />
          <path pathLength="1" d="M10 42h22" />
          <path pathLength="1" className="glyph__caret" d="M38 36v12" />
        </>
      ) : kind === "genres" ? (
        <>
          <path pathLength="1" d="M32 54V30" />
          <path pathLength="1" d="M32 38 16 24" />
          <path pathLength="1" d="M32 34 48 18" />
          <path pathLength="1" d="M16 24v-8M48 18l6-4" />
          <circle pathLength="1" cx="32" cy="30" r="2.6" />
          <circle pathLength="1" cx="16" cy="14" r="2.6" />
          <circle pathLength="1" cx="54" cy="12" r="2.6" />
        </>
      ) : (
        <>
          <rect pathLength="1" x="8" y="14" width="34" height="34" />
          <circle pathLength="1" cx="40" cy="31" r="17" />
          <circle pathLength="1" cx="40" cy="31" r="4" />
        </>
      )}
    </svg>
  );
}
