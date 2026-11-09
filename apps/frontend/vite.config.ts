import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";
import { auralisBackend } from "./server/backend.ts";

// `--mode fixture` serves synthetic contract data; otherwise /v1/ proxies to
// AURALIS_JAVA_ORIGIN. PORT overrides the default 4173 for dev and preview alike.
export default defineConfig(({ mode }) => {
  const port = Number(process.env.PORT ?? 4173);
  return {
    plugins: [react(), auralisBackend({ fixture: mode === "fixture" })],
    server: { host: "127.0.0.1", port, strictPort: true },
    preview: { host: "127.0.0.1", port, strictPort: true },
    build: { target: "es2022", sourcemap: false },
  };
});
