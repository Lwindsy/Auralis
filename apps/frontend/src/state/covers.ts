import { useEffect, useState } from "react";

export interface LoadedCover {
  url: string;
  image: HTMLImageElement;
}
interface Entry {
  promise: Promise<LoadedCover | null>;
  cover: LoadedCover | null;
  expires: number;
  users: number;
  bytes: number;
}
const cache = new Map<string, Entry>();
const MAX_BYTES = 32 * 1024 * 1024;

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
  if (!entry) {
    entry = {
      promise: Promise.resolve(null),
      cover: null,
      expires: 0,
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
          current.expires =
            Date.now() + (response.status === 404 ? 3600000 : 15000);
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
        current.expires = Date.now() + 15000;
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
    const request = acquireCover(key);
    let live = true;
    void request.promise.then((cover) => {
      if (live) setLoaded({ key, cover });
    });
    return () => {
      live = false;
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
