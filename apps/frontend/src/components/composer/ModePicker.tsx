import { MODE_COPY } from "../../api/messages.ts";
import type { Mode } from "../../api/types.ts";

const MODES: Mode[] = ["familiar", "explore", "surprise"];

export function ModePicker({
  value,
  onChange,
  disabled,
}: {
  value: Mode;
  onChange: (mode: Mode) => void;
  disabled: boolean;
}) {
  return (
    <div className="modes" role="radiogroup" aria-label="探索方式">
      {MODES.map((mode) => (
        <label
          key={mode}
          className="mode"
          title={MODE_COPY[mode].hint}
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
          <span className="mode__label">{MODE_COPY[mode].label}</span>
        </label>
      ))}
    </div>
  );
}
