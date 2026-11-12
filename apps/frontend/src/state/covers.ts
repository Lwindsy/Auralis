import { useEffect, useState } from "react";

export interface LoadedCover {
  url: string;
  image: HTMLImageElement;
}
interface Entry {
  promise: Promise<LoadedCover | null>;
  cover: LoadedCover | null;
  expires: number;
  retryable: boolean;
  users: number;
  bytes: number;
}
const cache = new Map<string, Entry>();
const MAX_BYTES = 32 * 1024 * 1024;
const RETRY_MS = 15000;
const MAX_RETRIES = 2;

export function coverUrl(id: string, size: 250 | 500 | 1200 = 500): string {
  return `/v1/catalog/albums/${encodeURIComponent(id)}/cover?size=${size}`;
}

// Only our image route can be fetched with the anonymous user's same-origin cookie.
export function coverKey(url: string | null | undefined): string | null {
  if (!url) return null;
  try {
    const value = new URL(url, window.location.origin);
    if (
      value.origin !== window.location.origin ||
      value.hash ||
      !/^\/v1\/catalog\/albums\/[0-9a-f-]{36}\/cover$/i.test(value.pathname) ||
      !["250", "500", "1200"].includes(value.searchParams.get("size") ?? "500")
    )
      return null;
    return value.pathname + value.search;
  } catch {
    return null;
  }
}

function trim() {
  let bytes = [...cache.values()].reduce((sum, entry) => sum + entry.bytes, 0);
  for (const [key, entry] of cache) {
    if (entry.users || entry.expires === 0) continue;
    if (cache.size <= 128 && bytes <= MAX_BYTES && entry.expires > Date.now())
      continue;
    cache.delete(key);
    bytes -= entry.bytes;
    if (entry.cover) URL.revokeObjectURL(entry.cover.url);
  }
}

export function acquireCover(key: string) {
  trim();
  let entry = cache.get(key);
  if (entry?.retryable && entry.expires <= Date.now()) {
    cache.delete(key);
    entry = undefined;
  }
  if (!entry) {
    entry = {
      promise: Promise.resolve(null),
      cover: null,
      expires: 0,
      retryable: false,
      users: 0,
      bytes: 0,
    };
    const current = entry;
    cache.set(key, current);
    current.promise = (async () => {
      let objectUrl: string | null = null;
      try {
        const response = await fetch(key, {
          credentials: "same-origin",
          signal: AbortSignal.timeout(14000),
        });
        if (!response.ok) {
          current.retryable =
            response.status >= 500 ||
            response.status === 429 ||
            response.status === 408;
          current.expires =
            Date.now() + (response.status === 404 ? 3600000 : RETRY_MS);
          return null;
        }
        const blob = await response.blob();
        if (
          !["image/jpeg", "image/png", "image/webp"].includes(blob.type) ||
          blob.size > 5 * 1024 * 1024
        )
          throw new Error("INVALID_COVER");
        objectUrl = URL.createObjectURL(blob);
        const image = new Image();
        image.decoding = "async";
        image.src = objectUrl;
        await image.decode();
        current.cover = { url: objectUrl, image };
        current.bytes =
          blob.size + image.naturalWidth * image.naturalHeight * 4;
        current.expires = Date.now() + 86400000;
        return current.cover;
      } catch {
        if (objectUrl) URL.revokeObjectURL(objectUrl);
        current.retryable = true;
        current.expires = Date.now() + RETRY_MS;
        return null;
      } finally {
        trim();
      }
    })();
  }
  entry.users++;
  cache.delete(key);
  cache.set(key, entry);
  const owned = entry;
  return {
    promise: owned.promise,
    retryAt: () => (owned.retryable ? owned.expires : null),
    release: () => {
      owned.users--;
      trim();
    },
  };
}

export function useCover(url: string | null | undefined): LoadedCover | null {
  const key = coverKey(url);
  const [loaded, setLoaded] = useState<{
    key: string;
    cover: LoadedCover | null;
  } | null>(null);
  useEffect(() => {
    if (!key) return;
    let request = acquireCover(key);
    let live = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let retries = 0;
    const watch = () => {
      void request.promise.then((cover) => {
        if (!live) return;
        setLoaded({ key, cover });
        const retryAt = request.retryAt();
        if (retryAt === null || retries >= MAX_RETRIES) return;
        timer = setTimeout(
          () => {
            request.release();
            request = acquireCover(key);
            retries++;
            watch();
          },
          Math.max(0, retryAt - Date.now()),
        );
      });
    };
    watch();
    return () => {
      live = false;
      clearTimeout(timer);
      request.release();
    };
  }, [key]);
  return loaded?.key === key ? loaded.cover : null;
}

export function prefetchCover(url: string | null | undefined) {
  const key = coverKey(url);
  if (!key) return;
  const request = acquireCover(key);
  void request.promise.finally(request.release);
}
