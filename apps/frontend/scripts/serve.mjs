import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";

const fixtureMode = process.argv.includes("--fixture");
const port = Number(process.env.PORT ?? 4173);
const origin = `http://127.0.0.1:${port}`;
const java = process.env.AURALIS_JAVA_ORIGIN
  ? new URL(process.env.AURALIS_JAVA_ORIGIN)
  : null;
if (java && !["http:", "https:"].includes(java.protocol))
  throw new Error("Java origin must be HTTP(S)");
const fixtures = new Map();
const FixtureBackend = fixtureMode
  ? (await import("../tests/fixtures.mjs")).FixtureBackend
  : null;
createServer(async (req, res) => {
  try {
    res.setHeader("Cache-Control", "no-store");
    const path = new URL(req.url, origin).pathname;
    if (path.startsWith("/v1/")) {
      if (req.method !== "GET" && req.headers.origin !== origin) {
        res.writeHead(403).end();
        return;
      }
      const chunks = [];
      let size = 0;
      for await (const chunk of req) {
        size += chunk.length;
        if (size > 128000) {
          res.writeHead(413).end();
          return;
        }
        chunks.push(chunk);
      }
      const body = Buffer.concat(chunks).toString();
      if (fixtureMode) {
        let id = req.headers.cookie?.match(/(?:^|; )fixture_id=([^;]+)/)?.[1];
        if (!fixtures.has(id)) {
          id = randomUUID();
          fixtures.set(id, new FixtureBackend());
          res.setHeader(
            "Set-Cookie",
            `fixture_id=${id}; HttpOnly; SameSite=Lax; Path=/`,
          );
        }
        const result = fixtures
          .get(id)
          .handle(
            req.method,
            req.url,
            body ? JSON.parse(body) : undefined,
            req.headers,
          );
        res
          .writeHead(result.status, { "Content-Type": "application/json" })
          .end(JSON.stringify(result.body));
      } else if (java) {
        const headers = { ...req.headers };
        delete headers.host;
        delete headers.connection;
        delete headers["content-length"];
        const upstream = await fetch(new URL(req.url, java), {
          method: req.method,
          headers,
          body: body || undefined,
          redirect: "manual",
          signal: AbortSignal.timeout(15000),
        });
        res.statusCode = upstream.status;
        for (const name of ["content-type", "cache-control"])
          if (upstream.headers.has(name))
            res.setHeader(name, upstream.headers.get(name));
        const cookies = upstream.headers.getSetCookie();
        if (cookies.length) res.setHeader("Set-Cookie", cookies);
        res.end(Buffer.from(await upstream.arrayBuffer()));
      } else
        res
          .writeHead(503, { "Content-Type": "application/json" })
          .end(JSON.stringify({ code: "JAVA_NOT_CONFIGURED" }));
      return;
    }
    const files = {
      "/": ["index.html", "text/html; charset=utf-8"],
      "/app.js": ["app.js", "text/javascript"],
      "/style.css": ["style.css", "text/css"],
    };
    if (!files[path]) {
      res.writeHead(404).end();
      return;
    }
    const [file, type] = files[path];
    let data = await readFile(new URL(`../dist/${file}`, import.meta.url));
    if (fixtureMode && file === "index.html")
      data = Buffer.from(
        data
          .toString()
          .replace(
            "<main>",
            '<main><p class="note">契约样例模式 · 合成数据，不是真实推荐服务</p>',
          ),
      );
    res
      .writeHead(200, {
        "Content-Type": type,
        "X-Content-Type-Options": "nosniff",
      })
      .end(data);
  } catch {
    if (!res.headersSent)
      res.writeHead(502, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ code: "UPSTREAM_UNAVAILABLE" }));
  }
}).listen(port, "127.0.0.1", () =>
  console.log(
    `${origin} (${fixtureMode ? "contract fixtures only" : "Java API proxy"})`,
  ),
);
