import type { Mode } from "../../api/types.ts";
import { useCopy } from "../../i18n/index.ts";

const MODES: Mode[] = ["familiar", "explore", "surprise"];

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
  const words = useCopy().modes;
  return (
    <div className="modes">
      <div className="modes__row" role="radiogroup" aria-label={words.group}>
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
              aria-label={words[mode].label}
              onChange={() => onChange(mode)}
            />
            <span>{words[mode].label}</span>
          </label>
        ))}
      </div>
      {/* One sentence on what the chosen position means (it replays on a new choice). */}
      <p className="modes__say" key={value} aria-live="polite">
        {words[value].line}
      </p>
    </div>
  );
}
