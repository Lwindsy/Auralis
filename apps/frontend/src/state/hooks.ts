import { useEffect, useState, useSyncExternalStore } from "react";

interface VersionedStore {
  subscribe: (listener: () => void) => () => void;
  getVersion: () => number;
}

/** Re-render whenever a mutable store reports a new version. */
export function useStore(store: VersionedStore): number {
  return useSyncExternalStore(
    store.subscribe,
    store.getVersion,
    store.getVersion,
  );
}

export function useDebounced<T>(value: T, delay: number): T {
  const [current, setCurrent] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setCurrent(value), delay);
    return () => clearTimeout(timer);
  }, [value, delay]);
  return current;
}

/** Whole seconds this page has watched `key` while active (client clock only). */
export function useElapsed(key: string | null, active: boolean): number {
  const [tick, setTick] = useState(() => {
    const now = Date.now();
    return { key, start: now, now };
  });
  useEffect(() => {
    if (!active) return;
    const timer = setInterval(() => {
      const now = Date.now();
      setTick((current) =>
        current.key === key ? { ...current, now } : { key, start: now, now },
      );
    }, 1000);
    return () => clearInterval(timer);
  }, [active, key]);
  return tick.key === key
    ? Math.max(0, Math.round((tick.now - tick.start) / 1000))
    : 0;
}
