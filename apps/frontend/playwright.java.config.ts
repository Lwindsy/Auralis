import { defineConfig } from "@playwright/test";

if (!process.env.AURALIS_JAVA_ORIGIN)
  throw new Error("Set AURALIS_JAVA_ORIGIN to the isolated Java harness");
const channel = process.env.AURALIS_BROWSER_CHANNEL ?? "chrome";

export default defineConfig({
  testDir: "./tests/java-browser",
  workers: 1,
  use: {
    baseURL: "http://127.0.0.1:4188",
    browserName: "chromium",
    ...(channel ? { channel } : {}),
    ...(process.env.AURALIS_CHROMIUM_PATH
      ? { launchOptions: { executablePath: process.env.AURALIS_CHROMIUM_PATH } }
      : {}),
    trace: "retain-on-failure",
  },
  webServer: {
    env: { PORT: "4188" },
    command: "npm run build && npm run preview",
    url: "http://127.0.0.1:4188",
    reuseExistingServer: false,
  },
});
