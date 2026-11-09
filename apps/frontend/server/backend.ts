import { randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import type { IncomingMessage, ServerResponse } from "node:http";
import { resolve } from "node:path";
import type { Plugin } from "vite";

type Next = (error?: unknown) => void;
interface Fixture {
  handle(
    method: string,
    url: string,
    body: unknown,
    headers: Record<string, string>,
  ): { status: number; body: unknown };
}

const FIXTURE_META = '<meta name="auralis-fixture" content="true" />';
const MAX_BODY = 128000;

/**
 * Local same-origin `/v1/` for development and preview. Fixture mode serves synthetic
 * contract data; otherwise requests are forwarded to AURALIS_JAVA_ORIGIN without rewriting
 * Origin, Cookie or CSRF. This is a development tool, not a production deployment.
 */
export function auralisBackend({ fixture }: { fixture: boolean }): Plugin {
  const java = process.env.AURALIS_JAVA_ORIGIN
    ? new URL(process.env.AURALIS_JAVA_ORIGIN)
    : null;
  if (java && !["http:", "https:"].includes(java.protocol))
    throw new Error("Java origin must be HTTP(S)");
  const sessions = new Map<string, Fixture>();
  let Backend: (new () => Fixture) | null = null;

  async function fixtureFor(req: IncomingMessage, res: ServerResponse) {
    Backend ??= (await import("../tests/fixtures.ts"))
      .FixtureBackend as new () => Fixture;
    let id = req.headers.cookie?.match(/(?:^|; )fixture_id=([^;]+)/)?.[1];
    if (!id || !sessions.has(id)) {
      id = randomUUID();
      sessions.set(id, new Backend());
      res.setHeader(
        "Set-Cookie",
        `fixture_id=${id}; HttpOnly; SameSite=Lax; Path=/`,
      );
    }
    return sessions.get(id)!;
  }

  function middleware(port: number) {
    const origin = `http://127.0.0.1:${port}`;
    return async (req: IncomingMessage, res: ServerResponse, next: Next) => {
      res.setHeader("X-Content-Type-Options", "nosniff");
      const path = new URL(req.url ?? "/", origin).pathname;
      if (!path.startsWith("/v1/")) return next();
      res.setHeader("Cache-Control", "no-store");
      try {
        if (req.method !== "GET" && req.headers.origin !== origin) {
          res.writeHead(403).end();
          return;
        }
        const chunks: Buffer[] = [];
        let size = 0;
        for await (const chunk of req) {
          size += (chunk as Buffer).length;
          if (size > MAX_BODY) {
            res.writeHead(413).end();
            return;
          }
          chunks.push(chunk as Buffer);
        }
        const body = Buffer.concat(chunks).toString();
        if (fixture) {
          const backend = await fixtureFor(req, res);
          const result = backend.handle(
            req.method ?? "GET",
            req.url ?? "/",
            body ? JSON.parse(body) : undefined,
            req.headers as Record<string, string>,
          );
          res
            .writeHead(result.status, { "Content-Type": "application/json" })
            .end(JSON.stringify(result.body));
          return;
        }
        if (!java) {
          res
            .writeHead(503, { "Content-Type": "application/json" })
            .end(JSON.stringify({ code: "JAVA_NOT_CONFIGURED" }));
          return;
        }
        const headers = { ...req.headers } as Record<string, string>;
        delete headers.host;
        delete headers.connection;
        delete headers["content-length"];
        const upstream = await fetch(new URL(req.url ?? "/", java), {
          method: req.method,
          headers,
          body: body || undefined,
          redirect: "manual",
          signal: AbortSignal.timeout(15000),
        });
        res.statusCode = upstream.status;
        for (const name of ["content-type", "cache-control"]) {
          const value = upstream.headers.get(name);
          if (value) res.setHeader(name, value);
        }
        const cookies = upstream.headers.getSetCookie();
        if (cookies.length) res.setHeader("Set-Cookie", cookies);
        res.end(Buffer.from(await upstream.arrayBuffer()));
      } catch {
        if (!res.headersSent)
          res.writeHead(502, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ code: "UPSTREAM_UNAVAILABLE" }));
      }
    };
  }

  return {
    name: "auralis-backend",
    configureServer(server) {
      server.middlewares.use(middleware(server.config.server.port ?? 4173));
    },
    configurePreviewServer(server) {
      const port = server.config.preview.port ?? 4173;
      server.middlewares.use(middleware(port));
      if (fixture)
        server.middlewares.use(async (req, res, next) => {
          const path = new URL(req.url ?? "/", "http://localhost").pathname;
          if (path !== "/" && path !== "/index.html") return next();
          const html = await readFile(
            resolve(
              server.config.root,
              server.config.build.outDir,
              "index.html",
            ),
            "utf8",
          );
          res
            .writeHead(200, {
              "Content-Type": "text/html; charset=utf-8",
              "Cache-Control": "no-store",
            })
            .end(html.replace("<head>", `<head>\n    ${FIXTURE_META}`));
        });
    },
    transformIndexHtml(html) {
      return fixture
        ? html.replace("<head>", `<head>\n    ${FIXTURE_META}`)
        : html;
    },
  };
}
