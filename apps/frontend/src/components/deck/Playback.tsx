import { Fragment, useState, type CSSProperties, type MouseEvent } from "react";

const MARKER = /\[(R[1-9][0-9]*)\]/g;
/** Characters per second used for the "length" shown at the end of the line. */
const READ_RATE = 7;

type Part = string | { marker: string };

function split(text: string): Part[] {
  const parts: Part[] = [];
  let last = 0;
  for (const match of text.matchAll(MARKER)) {
    if (match.index > last) parts.push(text.slice(last, match.index));
    parts.push({ marker: match[1] ?? "" });
    last = match.index + match[0].length;
  }
  if (last < text.length) parts.push(text.slice(last));
  return parts;
}

export const charCount = (text: string) =>
  split(text).reduce(
    (sum, part) => sum + (typeof part === "string" ? [...part].length : 1),
    0,
  );

const clock = (seconds: number) =>
  `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;

/**
 * A piece of writing played like a track: a thin progress line with a playhead runs
 * left to right and the words appear exactly as far as it has travelled. The end of the
 * line shows the reading length. Once played the line rests; clicking anywhere on it
 * plays again from there. Everything is driven by CSS animations sharing one clock, so
 * the playhead and the text never drift apart. The whole text is in the DOM throughout.
 */
export function Playback({
  text,
  delay = 0,
  pace = 18,
  label,
  className,
}: {
  text: string;
  /** Milliseconds before this track starts (lets tracks play one after another). */
  delay?: number;
  /** Milliseconds per character. */
  pace?: number;
  label?: string;
  className?: string;
}) {
  const parts = split(text);
  const total = charCount(text);
  const span = total * pace;
  const [run, setRun] = useState({ key: 0, from: 0 });
  const start = run.key === 0 ? delay : -run.from * span;

  const seek = (event: MouseEvent<HTMLButtonElement>) => {
    const box = event.currentTarget.getBoundingClientRect();
    const from = Math.min(
      0.98,
      Math.max(0, (event.clientX - box.left) / box.width),
    );
    setRun((current) => ({ key: current.key + 1, from }));
  };

  let index = 0;
  const at = () => ({ "--t": `${start + index++ * pace}ms` }) as CSSProperties;
  return (
    <div
      className={["track", className ?? ""].join(" ")}
      key={run.key}
      style={
        {
          "--start": `${start}ms`,
          "--span": `${span}ms`,
        } as CSSProperties
      }
    >
      <div className="track__head">
        {label ? <span className="track__label mono">{label}</span> : null}
        <button
          type="button"
          className="track__line"
          aria-label={`从这里重新播放${label ? ` ${label}` : ""}`}
          onClick={seek}
        >
          <span className="track__fill" />
          <span className="track__head-dot" />
        </button>
        <span className="track__time mono">
          {clock(Math.max(1, Math.round(total / READ_RATE)))}
        </span>
      </div>
      <p className="track__text">
        {parts.map((part, i) =>
          typeof part === "string" ? (
            <Fragment key={i}>
              {[...part].map((char, j) => (
                <span key={j} className="type" style={at()}>
                  {char}
                </span>
              ))}
            </Fragment>
          ) : (
            <sup
              key={i}
              className="cite type"
              style={at()}
              title={`资料 ${part.marker}`}
            >
              {part.marker}
            </sup>
          ),
        )}
      </p>
    </div>
  );
}
