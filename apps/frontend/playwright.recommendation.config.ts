import { readFileSync } from "node:fs";
import { defineConfig } from "@playwright/test";

if (!process.env.AURALIS_PRODUCT_BROWSER_HARNESS)
  throw new Error(
    "Set AURALIS_PRODUCT_BROWSER_HARNESS to the isolated harness JSON",
  );
const harness = JSON.parse(
  readFileSync(process.env.AURALIS_PRODUCT_BROWSER_HARNESS, "utf8"),
) as { origin: string };
const channel = process.env.AURALIS_BROWSER_CHANNEL ?? "chrome";

export default defineConfig({
  testDir: "./tests/recommendation-browser",
  workers: 1,
  timeout: 60000,
  expect: { timeout: 15000 },
  use: {
    baseURL: "http://127.0.0.1:4189",
    browserName: "chromium",
    ...(channel ? { channel } : {}),
    ...(process.env.AURALIS_CHROMIUM_PATH
      ? { launchOptions: { executablePath: process.env.AURALIS_CHROMIUM_PATH } }
      : {}),
    trace: "retain-on-failure",
  },
  webServer: {
    env: { PORT: "4189", AURALIS_JAVA_ORIGIN: harness.origin },
    command: "npm run build && npm run preview",
    url: "http://127.0.0.1:4189",
    reuseExistingServer: false,
  },
});
