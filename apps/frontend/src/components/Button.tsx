import type { ButtonHTMLAttributes, MouseEvent, ReactNode, Ref } from "react";
import { Icon, type IconName } from "./Icon.tsx";

export type ButtonVariant =
  "primary" | "secondary" | "ghost" | "quiet" | "accent" | "link";

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: "sm" | "md" | "lg";
  icon?: IconName;
  iconAfter?: IconName;
  iconFilled?: boolean;
  /** Keeps the button focusable while refusing activation (aria-disabled). */
  locked?: boolean;
  iconOnly?: boolean;
  children?: ReactNode;
  ref?: Ref<HTMLButtonElement>;
}

export function Button({
  variant = "secondary",
  size = "md",
  icon,
  iconAfter,
  iconFilled,
  locked = false,
  iconOnly = false,
  className,
  onClick,
  type = "button",
  children,
  ref,
  ...rest
}: ButtonProps) {
  const handle = (event: MouseEvent<HTMLButtonElement>) => {
    if (locked) {
      event.preventDefault();
      event.stopPropagation();
      return;
    }
    onClick?.(event);
  };
  const iconSize = size === "sm" ? 16 : 18;
  return (
    <button
      ref={ref}
      type={type}
      className={[
        "btn",
        `btn--${variant}`,
        `btn--${size}`,
        iconOnly ? "btn--icon" : "",
        className ?? "",
      ]
        .filter(Boolean)
        .join(" ")}
      aria-disabled={locked || undefined}
      onClick={handle}
      {...rest}
    >
      {icon ? <Icon name={icon} size={iconSize} filled={iconFilled} /> : null}
      {children !== undefined && children !== null ? (
        <span className={iconOnly ? "visually-hidden" : "btn__label"}>
          {children}
        </span>
      ) : null}
      {iconAfter ? <Icon name={iconAfter} size={iconSize} /> : null}
    </button>
  );
}
