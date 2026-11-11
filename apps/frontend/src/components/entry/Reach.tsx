import { useEffect, useRef, useState, type CSSProperties } from "react";
import { useCopy } from "../../i18n/index.ts";
import { reachToListeners } from "../../state/draft.ts";

/** Three bands of the scale; what each means is shown only while the slider is moved. */
const ZONES = [
  { from: 0, to: 40 },
  { from: 40, to: 70 },
  { from: 70, to: 100 },
] as const;

const zoneOf = (value: number) => (value < 40 ? 0 : value < 70 ? 1 : 2);

/**
 * 传播度: how widely an album must have been heard, as a share of a 500-listener scale
 * (0% = nobody needs to have heard it, 100% = at least 500 listeners). A thin rail with a
 * filled run and two marks at 40% and 70%. While the thumb is being moved the three bands
 * fold out underneath, the current one lit; they fold away shortly after letting go.
 */
export function Reach({
  value,
  disabled,
  onChange,
}: {
  value: number;
  disabled: boolean;
  onChange: (value: number) => void;
}) {
  const [active, setActive] = useState(false);
  const words = useCopy().reach;
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => clearTimeout(timer.current), []);
  const show = () => {
    clearTimeout(timer.current);
    setActive(true);
  };
  const hideSoon = (delay: number) => {
    clearTimeout(timer.current);
    timer.current = setTimeout(() => setActive(false), delay);
  };
  const zone = zoneOf(value);
  const listeners = reachToListeners(value);
  return (
    <div
      className="reach"
      data-active={active || undefined}
      style={{ "--p": value } as CSSProperties}
    >
      <div className="reach__head">
        <label className="reach__label" htmlFor="reach-input">
          {words.label}
        </label>
        <span className="reach__value mono" aria-hidden="true">
          {value}
          <small>%</small>
        </span>
      </div>
      <div className="reach__rail">
        <span className="reach__mark" style={{ "--at": 40 } as CSSProperties} />
        <span className="reach__mark" style={{ "--at": 70 } as CSSProperties} />
        <input
          id="reach-input"
          type="range"
          min={0}
          max={100}
          step={1}
          value={value}
          disabled={disabled}
          aria-valuetext={
            value === 0 ? words.anyone(value) : words.atLeast(value, listeners)
          }
          onChange={(event) => onChange(Number(event.target.value))}
          onPointerDown={show}
          onPointerUp={() => hideSoon(700)}
          onPointerCancel={() => hideSoon(300)}
          onKeyDown={(event) => {
            if (
              event.key.startsWith("Arrow") ||
              /^(Home|End|Page)/.test(event.key)
            ) {
              show();
              hideSoon(1600);
            }
          }}
          onBlur={() => hideSoon(0)}
        />
      </div>
      <ol className="reach__zones" aria-hidden="true">
        {ZONES.map((item, index) => (
          <li
            key={item.from}
            data-on={index === zone || undefined}
            style={
              {
                "--from": item.from,
                "--to": item.to,
                "--i": index,
              } as CSSProperties
            }
          >
            <span className="mono">
              {item.from} – {item.to}%
            </span>
            <span>{words.zones[index]}</span>
          </li>
        ))}
      </ol>
    </div>
  );
}
