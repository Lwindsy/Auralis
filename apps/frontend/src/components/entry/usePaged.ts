import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "../../api/client.ts";
import { note } from "../../api/messages.ts";

export interface Page<T> {
  items: T[];
  next_offset: number | null;
}

export interface PagedState<T> {
  items: T[];
  next: number | null;
  status: "idle" | "loading" | "ready" | "error";
  /** Message key of the last failure; views show it in the current language. */
  error: string | null;
  loadingMore: boolean;
}

const initial = <T>(): PagedState<T> => ({
  items: [],
  next: null,
  status: "idle",
  error: null,
  loadingMore: false,
});

/** The message key that explains a failed page load (unknown keys fall back). */
function describe(error: unknown, fallback: string): string {
  if (error instanceof DOMException && error.name === "TimeoutError")
    return "CATALOG_TIMEOUT";
  if (error instanceof ApiError) {
    const key = error.details?.message_key ?? error.message;
    return note(key) ? key : fallback;
  }
  return fallback;
}

/**
 * Loads catalog pages for `key`; a new key cancels older requests so late answers never
 * replace newer ones. `key === null` means "nothing to load".
 */
export function usePaged<T>(
  key: string | null,
  load: (offset: number, signal: AbortSignal) => Promise<Page<T>>,
  fallback = "CATALOG_UNAVAILABLE",
) {
  const [state, setState] = useState<PagedState<T>>(initial);
  const controller = useRef<AbortController | null>(null);
  const loadRef = useRef(load);
  useEffect(() => {
    loadRef.current = load;
  });

  const run = useCallback(
    async (offset: number, append: boolean) => {
      controller.current?.abort();
      const current = new AbortController();
      controller.current = current;
      setState((previous) =>
        append
          ? { ...previous, loadingMore: true, error: null }
          : { ...initial<T>(), status: "loading" },
      );
      try {
        const page = await loadRef.current(
          offset,
          AbortSignal.any([current.signal, AbortSignal.timeout(15000)]),
        );
        if (current.signal.aborted) return;
        setState((previous) => ({
          items: append ? [...previous.items, ...page.items] : page.items,
          next: page.next_offset,
          status: "ready",
          error: null,
          loadingMore: false,
        }));
      } catch (error) {
        if (current.signal.aborted) return;
        setState((previous) => ({
          ...previous,
          status: append ? previous.status : "error",
          error: describe(error, fallback),
          loadingMore: false,
        }));
      }
    },
    [fallback],
  );

  useEffect(() => {
    if (key === null) {
      controller.current?.abort();
      setState(initial());
      return;
    }
    void run(0, false);
    return () => controller.current?.abort();
  }, [key, run]);

  const loadMore = useCallback(() => {
    if (state.next !== null && !state.loadingMore) void run(state.next, true);
  }, [run, state.next, state.loadingMore]);

  const retry = useCallback(() => {
    if (key !== null) void run(0, false);
  }, [key, run]);

  return { ...state, loadMore, retry };
}
