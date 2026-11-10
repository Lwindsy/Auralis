import { useState, type CSSProperties, type ReactNode } from "react";

export type ReactionKind = "like" | "meh" | "heard";

const ICON: Record<ReactionKind, ReactNode> = {
  like: (
    <path d="M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10Z" />
  ),
  meh: <path d="M5 13c2.2-2 4.4-2 7 0s4.8 2 7 0" />,
  heard: (
    <path pathLength="1" className="react__check" d="m5 12.5 4.2 4.2L19 7" />
  ),
};

/** Small particles that fly from the button: hearts rise, a sigh drifts, a ring ripples. */
function Burst({ kind }: { kind: ReactionKind }) {
  if (kind === "heard")
    return (
      <span className="burst burst--ring" aria-hidden="true">
        <span />
        <span />
      </span>
    );
  const count = kind === "like" ? 9 : 5;
  return (
    <span className={`burst burst--${kind}`} aria-hidden="true">
      {Array.from({ length: count }, (_, i) => (
        <svg
          key={i}
          viewBox="0 0 24 24"
          style={
            {
              "--a": `${-150 + (i * 120) / (count - 1)}deg`,
              "--d": `${26 + ((i * 7) % 3) * 9}px`,
              "--t": `${i * 22}ms`,
            } as CSSProperties
          }
        >
          {kind === "like" ? (
            <path d="M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10Z" />
          ) : (
            <circle cx="12" cy="12" r="4" />
          )}
        </svg>
      ))}
    </span>
  );
}

/**
 * One feedback button. Choosing it plays a short burst matched to the feeling, then the
 * button settles into a dimmed "chosen" state; choosing it again takes it back.
 *
 * After any choice the reactions rest for a moment (`resting`): a thin ring draws itself
 * around the chosen icon while further presses are ignored, so a record cannot be flipped
 * back and forth faster than it can be noted down.
 */
export function Reaction({
  kind,
  label,
  done,
  disabled,
  resting,
  settling,
  title,
  onToggle,
}: {
  kind: ReactionKind;
  label: string;
  done: boolean;
  disabled?: boolean;
  /** All reactions are briefly resting after a choice; presses are ignored. */
  resting?: boolean;
  /** This is the button that was just pressed: its ring runs. */
  settling?: boolean;
  title?: string;
  onToggle: () => void;
}) {
  const [burst, setBurst] = useState(0);
  return (
    <button
      type="button"
      className="react"
      data-kind={kind}
      data-done={done || undefined}
      data-resting={resting || undefined}
      data-settling={settling || undefined}
      aria-pressed={done}
      aria-disabled={resting || undefined}
      disabled={disabled}
      title={title}
      onClick={() => {
        if (resting) return;
        if (!done) setBurst((value) => value + 1);
        onToggle();
      }}
    >
      <span className="react__glyph">
        <svg className="react__icon" viewBox="0 0 24 24" aria-hidden="true">
          {ICON[kind]}
        </svg>
        <svg className="react__ring" viewBox="0 0 32 32" aria-hidden="true">
          <circle cx="16" cy="16" r="15" pathLength="1" />
        </svg>
      </span>
      <span className="react__label">{label}</span>
      {burst ? <Burst key={burst} kind={kind} /> : null}
    </button>
  );
}
