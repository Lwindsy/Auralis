import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";
import { auralisyBackend } from "./server/backend.ts";

// `--mode fixture` serves synthetic contract data; otherwise /v1/ proxies to
// AURALIS_JAVA_ORIGIN. PORT overrides the default 4173 for dev and preview alike.
export default defineConfig(({ mode }) => {
  const port = Number(process.env.PORT ?? 4173);
  return {
    plugins: [react(), auralisyBackend({ fixture: mode === "fixture" })],
    server: { host: "127.0.0.1", port, strictPort: true },
    preview: { host: "127.0.0.1", port, strictPort: true },
    build: {
      target: "es2022",
      sourcemap: false,
      rolldownOptions: {
        output: {
          // Three long-lived pieces instead of one: React, the generated contract
          // validators, and the app itself. Each changes on its own schedule, so a
          // release that only touches the app keeps the other two cached.
          codeSplitting: {
            groups: [
              {
                name: "react",
                test: /node_modules[\\/](react|react-dom|scheduler)[\\/]/,
              },
              { name: "contract", test: /src[\\/]generated[\\/]/ },
            ],
          },
        },
      },
    },
  };
});
