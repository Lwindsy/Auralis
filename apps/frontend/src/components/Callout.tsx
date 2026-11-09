import type { ReactNode } from "react";
import { Icon, type IconName } from "./Icon.tsx";

export function Callout({
  tone = "neutral",
  icon = "info",
  action,
  children,
  role,
}: {
  tone?: "neutral" | "info" | "warning" | "danger" | "positive";
  icon?: IconName;
  action?: ReactNode;
  children: ReactNode;
  role?: "status" | "alert";
}) {
  return (
    <div className={`callout callout--${tone}`} role={role}>
      <Icon name={icon} size={17} className="callout__icon" />
      <div className="callout__body">{children}</div>
      {action ? <div className="callout__action">{action}</div> : null}
    </div>
  );
}
