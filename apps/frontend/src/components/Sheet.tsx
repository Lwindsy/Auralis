import { useEffect, useRef, useState, type ReactNode } from "react";

interface SheetProps {
  open: boolean;
  onClose: () => void;
  labelledBy: string;
  width?: "md" | "lg";
  /** CSS selector focused after opening; defaults to the browser's dialog focus rule. */
  initialFocus?: string;
  className?: string;
  children: ReactNode;
}

const EXIT_MS = 260;

/** Side sheet on a native modal dialog: focus trap, Escape and inert background for free. */
export function Sheet({
  open,
  onClose,
  labelledBy,
  width = "md",
  initialFocus,
  className,
  children,
}: SheetProps) {
  const ref = useRef<HTMLDialogElement>(null);
  const [mounted, setMounted] = useState(open);

  if (open && !mounted) setMounted(true);

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open) {
      if (!dialog.open) {
        dialog.showModal();
        if (initialFocus)
          dialog.querySelector<HTMLElement>(initialFocus)?.focus();
      }
      return;
    }
    if (!dialog.open) return;
    const timer = setTimeout(() => {
      dialog.close();
      setMounted(false);
    }, EXIT_MS);
    return () => clearTimeout(timer);
  }, [open, mounted, initialFocus]);

  if (!mounted) return null;
  return (
    <dialog
      ref={ref}
      className={["sheet", `sheet--${width}`, className ?? ""].join(" ")}
      aria-labelledby={labelledBy}
      data-closing={!open || undefined}
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
      onClick={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div className="sheet__panel">{children}</div>
    </dialog>
  );
}
