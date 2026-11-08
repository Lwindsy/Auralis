import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./tests/browser",
  fullyParallel: false,
  workers: 1,
  use: {
    baseURL: "http://127.0.0.1:4187",
    browserName: "chromium",
    channel: "chrome",
    trace: "retain-on-failure",
  },
  webServer: {
    env: { PORT: "4187" },
    command: "npm run build && npm run dev -- --fixture",
    url: "http://127.0.0.1:4187",
    reuseExistingServer: false,
  },
});
