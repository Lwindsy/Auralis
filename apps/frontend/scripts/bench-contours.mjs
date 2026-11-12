// Frame-rate check of the background map on this machine's real GPU: WebGL against the
// 2D canvas, at a Retina-like 2x resolution, with the CPU at full speed and slowed down.
// Run with `npm run bench:contours` (builds first). Opens a visible Chrome window, since
// headless browsers emulate the GPU on the CPU. Results are printed as a table.
import { spawn } from "node:child_process";
import { chromium } from "@playwright/test";

const PORT = 4188;
const URL_BASE = `http://127.0.0.1:${PORT}/`;
const SECONDS = 5;
const RATES = [1, 4, 6];
const channel = process.env.AURALIS_BROWSER_CHANNEL ?? "chrome";

const server = spawn(
  "npx",
  [
    "vite",
    "preview",
    "--mode",
    "fixture",
    "--port",
    String(PORT),
    "--strictPort",
  ],
  { shell: true, stdio: "ignore" },
);
const stop = () => {
  if (process.platform === "win32")
    spawn("taskkill", ["/pid", String(server.pid), "/t", "/f"], {
      stdio: "ignore",
    });
  else server.kill();
};

async function ready() {
  for (let i = 0; i < 60; i++) {
    try {
      if ((await fetch(URL_BASE)).ok) return;
    } catch {
      // not up yet
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error("preview server did not start");
}

async function measure(browser, renderer, rate) {
  const context = await browser.newContext({
    viewport: { width: 1512, height: 982 },
    deviceScaleFactor: 2,
  });
  const page = await context.newPage();
  const cdp = await context.newCDPSession(page);
  await page.goto(`${URL_BASE}?contours=${renderer}`);
  await page.waitForTimeout(3000);
  const drawn = await page.locator(".contours").getAttribute("data-renderer");
  const gpu = await page.evaluate(() => {
    const gl = document.createElement("canvas").getContext("webgl2");
    const info = gl?.getExtension("WEBGL_debug_renderer_info");
    return info ? String(gl.getParameter(info.UNMASKED_RENDERER_WEBGL)) : "";
  });
  await cdp.send("Emulation.setCPUThrottlingRate", { rate });
  await page.evaluate(() => {
    window.__frames = [];
    let last = performance.now();
    const tick = (now) => {
      window.__frames.push(now - last);
      last = now;
      window.__raf = requestAnimationFrame(tick);
    };
    window.__raf = requestAnimationFrame(tick);
  });
  // A hand passing over the map, like a person moving the pointer.
  for (let i = 0; i < SECONDS * 20; i++) {
    await page.mouse.move(300 + ((i * 41) % 900), 200 + ((i * 29) % 500));
    await page.waitForTimeout(50);
  }
  const frames = await page.evaluate(() => {
    cancelAnimationFrame(window.__raf);
    return window.__frames.slice(2);
  });
  await context.close();
  const sorted = [...frames].sort((a, b) => a - b);
  const total = frames.reduce((sum, value) => sum + value, 0) / 1000;
  return {
    renderer: drawn,
    cpu: rate === 1 ? "full speed" : `${rate}x slower`,
    fps: Math.round(frames.length / total),
    "p95 frame ms": Math.round(sorted[Math.floor(sorted.length * 0.95)] ?? 0),
    "frames > 25ms": `${Math.round((100 * frames.filter((value) => value > 25).length) / Math.max(1, frames.length))}%`,
    gpu,
  };
}

try {
  await ready();
  const browser = await chromium.launch({
    headless: false,
    ...(channel ? { channel } : {}),
  });
  const rows = [];
  for (const rate of RATES)
    for (const renderer of ["webgl", "2d"])
      rows.push(await measure(browser, renderer, rate));
  await browser.close();
  console.log(`\nGPU: ${rows[0]?.gpu || "unknown"}`);
  console.table(rows.map(({ gpu, ...row }) => (void gpu, row)));
} finally {
  stop();
}
