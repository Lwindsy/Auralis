import { mkdir, copyFile } from "node:fs/promises";
import { build } from "esbuild";
// Generated assets are checked in so product-only checkouts can build offline.
await mkdir(new URL("../dist/", import.meta.url), { recursive: true });
await build({
  entryPoints: ["src/main.js"],
  bundle: true,
  format: "esm",
  outfile: "dist/app.js",
  minify: true,
});
for (const name of ["index.html", "style.css"])
  await copyFile(name, `dist/${name}`);
