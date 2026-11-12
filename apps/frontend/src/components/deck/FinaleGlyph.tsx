/**
 * Pictures for the two end-of-group choices. They stay still (moving bars read as sound
 * playing) and only answer a hover or focus, through deck.css:
 *
 * - doors: the start page's three doors in small; the middle one opens up a little.
 *   It stands for "go back and change what you asked for".
 * - fan: a sleeve in front, one behind and an empty dashed slot fanned out like the
 *   group's stack above; the fan spreads. It stands for "a few more albums".
 */
export function FinaleGlyph({ kind }: { kind: "doors" | "fan" }) {
  return (
    <svg
      className="choice__glyph"
      data-kind={kind}
      viewBox="0 0 120 56"
      aria-hidden="true"
      focusable="false"
    >
      {kind === "doors" ? (
        <>
          <rect
            className="glyph__side"
            x="17"
            y="14"
            width="18"
            height="30"
            rx="4"
          />
          <g className="glyph__door">
            <rect x="42" y="5" width="36" height="46" rx="5" />
            <path d="M50 19h20M50 26h13" />
          </g>
          <rect
            className="glyph__side"
            data-right
            x="85"
            y="14"
            width="18"
            height="30"
            rx="4"
          />
        </>
      ) : (
        <>
          <rect
            className="glyph__sleeve"
            data-back
            x="45"
            y="18"
            width="30"
            height="30"
            rx="2.5"
          />
          <rect
            className="glyph__sleeve"
            data-slot
            x="45"
            y="18"
            width="30"
            height="30"
            rx="2.5"
          />
          <rect
            className="glyph__sleeve"
            data-front
            x="45"
            y="18"
            width="30"
            height="30"
            rx="2.5"
          />
        </>
      )}
    </svg>
  );
}
