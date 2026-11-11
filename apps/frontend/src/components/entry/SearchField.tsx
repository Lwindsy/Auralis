import { useCopy } from "../../i18n/index.ts";
import { Icon } from "../Icon.tsx";

export function SearchField({
  id,
  label,
  placeholder,
  value,
  onChange,
  onSubmit,
}: {
  id: string;
  label: string;
  placeholder: string;
  value: string;
  onChange: (value: string) => void;
  onSubmit: () => void;
}) {
  const words = useCopy();
  return (
    <div className="search-field">
      <label htmlFor={id} className="visually-hidden">
        {label}
      </label>
      <Icon name="search" size={17} className="search-field__icon" />
      <input
        id={id}
        type="search"
        autoComplete="off"
        spellCheck={false}
        enterKeyHint="search"
        maxLength={200}
        placeholder={placeholder}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === "Enter") {
            event.preventDefault();
            onSubmit();
          }
        }}
      />
      {value ? (
        <button
          type="button"
          className="search-field__clear"
          aria-label={words.search.clear}
          onClick={() => onChange("")}
        >
          <Icon name="close" size={14} />
        </button>
      ) : null}
    </div>
  );
}
