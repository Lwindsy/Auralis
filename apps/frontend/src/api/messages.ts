import { copy } from "../i18n/index.ts";
import type { ProductState } from "./types.ts";

// Only reviewed local copy is ever displayed, in the page's current language. Unknown
// keys fall back to generic copy and never echo server text, stack traces or provider
// responses.

/** Generic copy for a failed operation, in the current language. */
export const genericFailure = () => copy().genericFailure;

export function message(
  key: string | null | undefined,
  fallback: string = genericFailure(),
): string {
  if (!key) return fallback;
  const value = copy().messages[key];
  return value === undefined || value === null ? fallback : value;
}

/** Copy for a key, or null when the key is known to need no visible note. */
export function note(key: string | null | undefined): string | null {
  if (!key) return null;
  const messages = copy().messages;
  if (key in messages) return messages[key] ?? null;
  return null;
}

export function isKnownKey(key: string | null | undefined): boolean {
  return !!key && key in copy().messages;
}

export function stateMessage(state: ProductState): string {
  const states = copy().states;
  if (state.status === "WAITING") return states.waiting!;
  if (state.status === "RUNNING") return states.running!;
  if (state.analysis_mode === "BASIC") return states.basic!;
  return states[state.status] ?? "";
}
