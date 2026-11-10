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
 * Three words; the chosen one stands out. The real feedback is the landscape behind the
 * page, which tightens into rings, unfolds or rises into long ridges with the choice.
 */
export function ModeWords({
  value,
  onChange,
  disabled,
}: {
  value: Mode;
  onChange: (mode: Mode) => void;
  disabled: boolean;
}) {
  return (
    <div className="modes">
      <div className="modes__row" role="radiogroup" aria-label="探索方式">
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
      </div>
      <p className="modes__say" key={value} aria-live="polite">
        {MODE_LINE[value]}
      </p>
    </div>
  );
}
