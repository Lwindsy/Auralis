import { useState, type CSSProperties } from "react";
import { MODE_COPY } from "../../api/messages.ts";
import type { Mode } from "../../api/types.ts";

const MODES: Mode[] = ["familiar", "explore", "surprise"];

/** What each position means, in one plain sentence. */
export const MODE_LINE: Record<Mode, string> = {
  familiar: "就在你给的方向里找，稳一点。",
  explore: "主线不变，顺手带你去相邻的风格。",
  surprise: "允许走远一点，可能遇到意想不到的。",
};

/**
 * Three words and a sliding line. The real feedback is the landscape behind the page,
 * which tightens into rings, unfolds or rises into long ridges with the choice. The
 * optional listener threshold folds out beside it.
 */
export function ModeWords({
  value,
  onChange,
  disabled,
  audience,
  audienceProblem,
  onAudience,
}: {
  value: Mode;
  onChange: (mode: Mode) => void;
  disabled: boolean;
  audience: string;
  audienceProblem: string | null;
  onAudience: (value: string) => void;
}) {
  const [more, setMore] = useState(() => audience !== "");
  return (
    <div className="modes">
      <div
        className="modes__row"
        role="radiogroup"
        aria-label="探索方式"
        style={{ "--mode": MODES.indexOf(value) } as CSSProperties}
      >
        {MODES.map((mode) => (
          <label
            key={mode}
            className="mode"
            data-checked={value === mode || undefined}
          >
            <input
              type="radio"
              name="mode"
              value={mode}
              checked={value === mode}
              disabled={disabled}
              aria-label={MODE_COPY[mode].label}
              onChange={() => onChange(mode)}
            />
            <span>{MODE_COPY[mode].label}</span>
          </label>
        ))}
        <span className="modes__line" aria-hidden="true" />
      </div>
      <p className="modes__say" key={value} aria-live="polite">
        {MODE_LINE[value]}
      </p>
      <div className="modes__more">
        <button
          type="button"
          className="modes__toggle"
          aria-expanded={more}
          onClick={() => setMore((open) => !open)}
        >
          {audience ? `听众 ≥ ${audience}` : "更多设置"}
        </button>
        {more ? (
          <label className="modes__field">
            <span>最低听众数（可选）</span>
            <input
              type="number"
              inputMode="numeric"
              min={1}
              step={1}
              placeholder="默认"
              value={audience}
              aria-invalid={!!audienceProblem || undefined}
              onChange={(event) => onAudience(event.target.value.trim())}
            />
          </label>
        ) : null}
        {audienceProblem ? (
          <span className="modes__error">{audienceProblem}</span>
        ) : null}
      </div>
    </div>
  );
}
