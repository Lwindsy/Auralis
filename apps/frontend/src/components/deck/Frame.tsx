/**
 * A hairline frame with one short light that runs once around the panel when it appears,
 * then rests as a faint line. Pure SVG: a rect with a normalised path length.
 */
export function Frame() {
  return (
    <svg className="frame" aria-hidden="true" focusable="false">
      <rect
        className="frame__line"
        x="0.5"
        y="0.5"
        width="100%"
        height="100%"
      />
      <rect
        className="frame__light"
        x="0.5"
        y="0.5"
        width="100%"
        height="100%"
        pathLength="100"
      />
    </svg>
  );
}
