import { defineConfig } from "@playwright/test";

// Installed Chrome by default; set AURALIS_BROWSER_CHANNEL= (empty) to use bundled Chromium.
const channel = process.env.AURALIS_BROWSER_CHANNEL ?? "chrome";

export default defineConfig({
  testDir: "./tests/browser",
  fullyParallel: false,
  workers: 1,
  timeout: 30000,
  use: {
    baseURL: "http://127.0.0.1:4187",
    browserName: "chromium",
    ...(channel ? { channel } : {}),
    ...(process.env.AURALIS_CHROMIUM_PATH
      ? { launchOptions: { executablePath: process.env.AURALIS_CHROMIUM_PATH } }
      : {}),
    trace: "retain-on-failure",
  },
  webServer: {
    env: { PORT: "4187" },
    command: "npm run build && npm run preview:fixture",
    url: "http://127.0.0.1:4187",
    reuseExistingServer: false,
  },
});
