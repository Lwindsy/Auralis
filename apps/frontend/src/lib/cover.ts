// Deterministic sleeve artwork used while the product API keeps covers as placeholders.
// Same album → same sleeve on every device. The look follows restrained label sleeves:
// a quiet colour field, one graphic gesture, small typography. Nothing imitates a real cover.

// [field, gesture, ink-on-field] — bold label colours: each one can carry a whole page.
const PALETTES: readonly (readonly [string, string, string])[] = [
  ["#1f3a5f", "#e8c547", "#f4f1e8"],
  ["#c8442b", "#141414", "#f6efe6"],
  ["#e9c46a", "#1d1d1d", "#1d1d1d"],
  ["#0e5c4f", "#e6d3a3", "#f2eee4"],
  ["#141414", "#d94f30", "#f0ece4"],
  ["#e3ded3", "#1f3a5f", "#151515"],
  ["#4b2a6b", "#f2a65a", "#f6f0ff"],
  ["#2f4858", "#f6ae2d", "#eef3f6"],
  ["#a3b18a", "#2c3e33", "#1b1b1b"],
  ["#d8c3a5", "#8e3b2e", "#1d1a17"],
  ["#f2e8cf", "#bc4749", "#1c1c1c"],
  ["#7a1f2b", "#f1c6a8", "#f8ece6"],
  ["#2b2d42", "#ef8354", "#edf2f4"],
  ["#c9d6df", "#1e2022", "#1e2022"],
];

export type CoverStyle =
  "circle" | "split" | "stripes" | "block" | "ring" | "rule";
const STYLES: readonly CoverStyle[] = [
  "circle",
  "split",
  "stripes",
  "block",
  "ring",
  "rule",
];

/** FNV-1a with a murmur3 finaliser; stable across platforms. */
export function hash(text: string): number {
  let value = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    value ^= text.charCodeAt(i);
    value = Math.imul(value, 0x01000193);
  }
  value ^= value >>> 16;
  value = Math.imul(value, 0x85ebca6b);
  value ^= value >>> 13;
  value = Math.imul(value, 0xc2b2ae35);
  value ^= value >>> 16;
  return value >>> 0;
}

export interface CoverArt {
  style: CoverStyle;
  background: string;
  base: string;
  accent: string;
  ink: string;
  /** Roughly how much of the sleeve the mark covers (0–1); the field takes the rest. */
  markShare: number;
}

export function coverArt(seed: string): CoverArt {
  const h = hash(seed);
  const palette = PALETTES[h % PALETTES.length] ?? PALETTES[0]!;
  const style = STYLES[(h >>> 8) % STYLES.length] ?? "circle";
  const [field, mark, ink] = palette;
  const a = 38 + ((h >>> 12) % 30); // vertical position, %
  const b = 18 + ((h >>> 18) % 46); // horizontal position, %
  let background: string;
  let markShare: number;
  switch (style) {
    case "circle":
      background = `radial-gradient(circle at ${b + 14}% ${a + 6}%, ${mark} 0 31%, transparent 31.3%), ${field}`;
      markShare = 0.16;
      break;
    case "split":
      background = `linear-gradient(${(h >>> 4) % 2 ? 120 : 60}deg, ${field} 0 ${a + 8}%, ${mark} ${a + 8}% 100%)`;
      markShare = 1 - (a + 8) / 100;
      break;
    case "stripes":
      background = `repeating-linear-gradient(0deg, ${field} 0 9%, ${mark} 9% 10.5%), ${field}`;
      markShare = 0.14;
      break;
    case "block":
      background = `linear-gradient(${mark}, ${mark}) ${b}% 100% / 44% ${a + 10}% no-repeat, ${field}`;
      markShare = (0.44 * (a + 10)) / 100;
      break;
    case "ring":
      background = `radial-gradient(circle at 62% 58%, transparent 0 22%, ${mark} 22.3% 34%, transparent 34.3%), ${field}`;
      markShare = 0.15;
      break;
    case "rule":
      background = `linear-gradient(${mark}, ${mark}) 0 ${a + 12}% / 100% 3.5% no-repeat, ${field}`;
      markShare = 0.035;
      break;
  }
  return { style, background, base: field, accent: mark, ink, markShare };
}

export function initials(title: string): string {
  const letters = [...title.trim()].filter((ch) => /[\p{L}\p{N}]/u.test(ch));
  return (letters[0] ?? "♪").toUpperCase();
}

export const albumSeed = (title: string, artist: string) =>
  `${title}\u0000${artist}`;
