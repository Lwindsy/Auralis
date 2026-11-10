// The room's colour, worked out from the sleeve in a perceptual colour space (OKLab /
// OKLCH) so that "the same colour, quieter" and "clearly lighter" mean what they look like.

/** One colour of a sleeve and the share of the sleeve it covers. */
export interface Swatch {
  L: number;
  a: number;
  b: number;
  /** Share of the sleeve, 0–1; shares of one sleeve add up to 1. */
  area: number;
}

export interface Room {
  room: string;
  ink: string;
}

const srgbToLinear = (c: number) =>
  c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
const linearToSrgb = (c: number) =>
  c <= 0.0031308 ? 12.92 * c : 1.055 * c ** (1 / 2.4) - 0.055;

export function rgbToOklab(r: number, g: number, b: number) {
  const lr = srgbToLinear(r / 255);
  const lg = srgbToLinear(g / 255);
  const lb = srgbToLinear(b / 255);
  const l = Math.cbrt(
    0.4122214708 * lr + 0.5363325363 * lg + 0.0514459929 * lb,
  );
  const m = Math.cbrt(
    0.2119034982 * lr + 0.6806995451 * lg + 0.1073969566 * lb,
  );
  const s = Math.cbrt(
    0.0883024619 * lr + 0.2817188376 * lg + 0.6299787005 * lb,
  );
  return {
    L: 0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s,
    a: 1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s,
    b: 0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s,
  };
}

/** OKLab → linear sRGB (may fall outside 0–1 when out of gamut). */
function oklabToLinear(L: number, a: number, b: number) {
  const l = (L + 0.3963377774 * a + 0.2158037573 * b) ** 3;
  const m = (L - 0.1055613458 * a - 0.0638541728 * b) ** 3;
  const s = (L - 0.0894841775 * a - 1.291485548 * b) ** 3;
  return [
    4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
    -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
    -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s,
  ] as const;
}

export function hexToOklab(hex: string) {
  const value = parseInt(hex.replace("#", "").slice(0, 6), 16);
  return rgbToOklab((value >> 16) & 255, (value >> 8) & 255, value & 255);
}

/** OKLCH → hex, lowering chroma until the colour fits in sRGB. */
export function oklchToHex(L: number, C: number, h: number) {
  let chroma = C;
  for (;;) {
    const rgb = oklabToLinear(L, chroma * Math.cos(h), chroma * Math.sin(h));
    if (rgb.every((c) => c >= -1e-4 && c <= 1 + 1e-4) || chroma <= 0) {
      return (
        "#" +
        rgb
          .map((c) =>
            Math.round(
              Math.max(0, Math.min(1, linearToSrgb(Math.max(0, c)))) * 255,
            )
              .toString(16)
              .padStart(2, "0"),
          )
          .join("")
      );
    }
    chroma = Math.max(0, chroma - 0.004);
  }
}

/** Relative luminance as APCA reads it (plain 2.4 exponent). */
function apcaY(hex: string) {
  const value = parseInt(hex.slice(1), 16);
  const c = [(value >> 16) & 255, (value >> 8) & 255, value & 255].map(
    (v) => (v / 255) ** 2.4,
  );
  return 0.2126729 * c[0]! + 0.7151522 * c[1]! + 0.072175 * c[2]!;
}

/** APCA lightness contrast (Lc) of text on a background; the sign gives the polarity. */
export function apcaContrast(text: string, background: string) {
  const soft = (y: number) => (y > 0.022 ? y : y + (0.022 - y) ** 1.414);
  const t = soft(apcaY(text));
  const g = soft(apcaY(background));
  if (g > t) {
    const sapc = (g ** 0.56 - t ** 0.57) * 1.14;
    return sapc < 0.1 ? 0 : (sapc - 0.027) * 100;
  }
  const sapc = (g ** 0.65 - t ** 0.62) * 1.14;
  return sapc > -0.1 ? 0 : (sapc + 0.027) * 100;
}

const chromaOf = (s: Swatch) => Math.hypot(s.a, s.b);
const clamp = (v: number, lo: number, hi: number) =>
  Math.max(lo, Math.min(hi, v));

/**
 * The room for a sleeve:
 *
 * 1. Salience picks the colour the eye takes as "the colour of this record": area (with
 *    diminishing returns) times how vivid it is, so a small hot accent can win over a big
 *    grey field, but a big coloured field wins over a speck.
 * 2. The room keeps that hue but is quieter: a large surface looks more saturated than the
 *    same colour on a small sleeve (area effect), so chroma is cut to ~55% and capped.
 * 3. Lightness follows the sleeve's overall lightness: darker records get a dark room,
 *    light ones a light room, and the room is always kept clearly apart from the sleeve's
 *    largest colour so the cover never melts into the wall.
 * 4. The text colour is whichever of a near-white or near-black (tinted with the same hue)
 *    reads better on the room by APCA contrast, and the room must give it Lc ≥ 75.
 */
export function roomFor(swatches: readonly Swatch[]): Room {
  const total = swatches.reduce((sum, s) => sum + s.area, 0) || 1;
  const parts = swatches.map((s) => ({ ...s, area: s.area / total }));
  const salience = (s: Swatch) =>
    s.area ** 0.75 * (0.4 + (1.6 * Math.min(chromaOf(s), 0.2)) / 0.2);
  const lead = parts.reduce((best, s) =>
    salience(s) > salience(best) ? s : best,
  );
  const big = parts.reduce((best, s) => (s.area > best.area ? s : best));
  const meanL = parts.reduce((sum, s) => sum + s.L * s.area, 0);
  const hue = Math.atan2(lead.b, lead.a);
  const natural = meanL < 0.62 ? "dark" : "light";
  const target = {
    dark: clamp(0.15 + 0.2 * meanL, 0.17, 0.29),
    light: clamp(0.8 + 0.12 * meanL, 0.86, 0.94),
  };
  const chroma = {
    dark: Math.min(chromaOf(lead) * 0.55, 0.075),
    light: Math.min(chromaOf(lead) * 0.55, 0.05),
  };
  const light = oklchToHex(0.96, 0.01, hue);
  const deep = oklchToHex(0.2, 0.02, hue);

  // Every candidate lightness of both kinds of room is tried; the chosen one reads well
  // (APCA Lc ≥ 75 for the better ink), stays clearly apart from the sleeve's largest
  // colour, and sits closest to the natural lightness for this sleeve. A room of the other
  // kind (light record on a dark wall, or the reverse) is only taken when it must be.
  let best: { cost: number; room: string; ink: string } | null = null;
  let fallback: { score: number; room: string; ink: string } | null = null;
  for (const kind of ["dark", "light"] as const) {
    const [from, to] = kind === "dark" ? [0.12, 0.34] : [0.8, 0.97];
    for (let L = from; L <= to + 1e-9; L += 0.01) {
      const room = oklchToHex(L, chroma[kind], hue);
      const onLight = Math.abs(apcaContrast(light, room));
      const onDeep = Math.abs(apcaContrast(deep, room));
      const ink = onLight >= onDeep ? light : deep;
      const contrast = Math.max(onLight, onDeep);
      const apart = Math.abs(L - big.L);
      if (contrast >= 75 && apart >= 0.07) {
        // A near-black sleeve reads better on a charcoal wall than on a blacker one.
        const sink = kind === "dark" && big.L < 0.26 && L < big.L ? 0.05 : 0;
        const cost =
          Math.abs(L - target[kind]) + (kind === natural ? 0 : 0.25) + sink;
        if (!best || cost < best.cost) best = { cost, room, ink };
      }
      const score = contrast + 300 * Math.min(apart, 0.07);
      if (!fallback || score > fallback.score) fallback = { score, room, ink };
    }
  }
  const pick = best ?? fallback!;
  return { room: pick.room, ink: pick.ink };
}

/** Swatches of a placeholder sleeve: its field and its mark, by how much each covers. */
export function placeholderSwatches(
  field: string,
  mark: string,
  markShare: number,
): Swatch[] {
  return [
    { ...hexToOklab(field), area: 1 - markShare },
    { ...hexToOklab(mark), area: markShare },
  ];
}

/**
 * Swatches of a real cover image: a small copy is clustered in OKLab (k-means, k = 5,
 * seeded deterministically from the most different pixels). Pixels near the middle count
 * a little more, as the eye rests there. Rejects if the image cannot be read (no CORS).
 */
export function imageSwatches(
  source: string | HTMLImageElement,
): Promise<Swatch[]> {
  return new Promise((resolve, reject) => {
    const image = typeof source === "string" ? new Image() : source;
    if (typeof source === "string") {
      image.crossOrigin = "anonymous";
      image.referrerPolicy = "no-referrer";
      image.decoding = "async";
      image.onerror = () => reject(new Error("COVER_UNREADABLE"));
    }
    const sample = () => {
      try {
        const size = 40;
        const canvas = document.createElement("canvas");
        canvas.width = size;
        canvas.height = size;
        const ctx = canvas.getContext("2d", { willReadFrequently: true });
        if (!ctx) throw new Error("NO_CANVAS");
        ctx.drawImage(image, 0, 0, size, size);
        const data = ctx.getImageData(0, 0, size, size).data;
        const points: { L: number; a: number; b: number; w: number }[] = [];
        for (let y = 0; y < size; y++)
          for (let x = 0; x < size; x++) {
            const i = (y * size + x) * 4;
            if (data[i + 3]! < 128) continue;
            const d = Math.hypot(x / size - 0.5, y / size - 0.5);
            points.push({
              ...rgbToOklab(data[i]!, data[i + 1]!, data[i + 2]!),
              w: 1 - 0.45 * Math.min(1, d / 0.7),
            });
          }
        resolve(cluster(points, 5));
      } catch (error) {
        reject(error instanceof Error ? error : new Error("COVER_UNREADABLE"));
      }
    };
    if (typeof source === "string") {
      image.onload = sample;
      image.src = source;
    } else sample();
  });
}

function cluster(
  points: { L: number; a: number; b: number; w: number }[],
  k: number,
): Swatch[] {
  if (!points.length) return [];
  const distance = (
    p: { L: number; a: number; b: number },
    q: { L: number; a: number; b: number },
  ) => (p.L - q.L) ** 2 + (p.a - q.a) ** 2 + (p.b - q.b) ** 2;
  // Deterministic farthest-point seeding.
  const centres = [{ ...points[0]! }];
  while (centres.length < Math.min(k, points.length)) {
    let far = points[0]!;
    let best = -1;
    for (const p of points) {
      const near = Math.min(...centres.map((c) => distance(p, c)));
      if (near > best) {
        best = near;
        far = p;
      }
    }
    centres.push({ ...far });
  }
  const sums = centres.map(() => ({ L: 0, a: 0, b: 0, w: 0 }));
  for (let round = 0; round < 8; round++) {
    for (const s of sums) s.L = s.a = s.b = s.w = 0;
    for (const p of points) {
      let index = 0;
      let best = Infinity;
      centres.forEach((c, i) => {
        const d = distance(p, c);
        if (d < best) {
          best = d;
          index = i;
        }
      });
      const s = sums[index]!;
      s.L += p.L * p.w;
      s.a += p.a * p.w;
      s.b += p.b * p.w;
      s.w += p.w;
    }
    sums.forEach((s, i) => {
      if (s.w > 0)
        centres[i] = { L: s.L / s.w, a: s.a / s.w, b: s.b / s.w, w: 1 };
    });
  }
  const weight = sums.reduce((sum, s) => sum + s.w, 0) || 1;
  return centres
    .map((c, i) => ({ L: c.L, a: c.a, b: c.b, area: sums[i]!.w / weight }))
    .filter((s) => s.area > 0.01);
}
