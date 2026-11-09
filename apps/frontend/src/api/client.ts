import * as validators from "../generated/validators.js";
import routes from "../generated/routes.json" with { type: "json" };
import type { ProductContract, ProductError } from "./types.ts";

export type DefinitionName = keyof ProductContract;

interface RouteDefinition {
  request: DefinitionName | null;
  responses: Record<string, DefinitionName | null>;
}

const routeTable = routes as Record<string, RouteDefinition>;

/** Error carrying a safe code; details only ever come from a validated ProductError. */
export class ApiError extends Error {
  readonly details: ProductError | null;
  readonly status: number;
  constructor(code: string, details: ProductError | null = null, status = 0) {
    super(code);
    this.name = "ApiError";
    this.details = details;
    this.status = status;
  }
}

export function validate<T extends DefinitionName>(
  name: T,
  value: unknown,
): ProductContract[T] {
  const check = (validators as Record<string, (value: unknown) => boolean>)[
    name
  ];
  if (!check?.(value)) throw new ApiError("INVALID_RESPONSE");
  return value as ProductContract[T];
}

export type Fetcher = (url: string, init: RequestInit) => Promise<Response>;

export interface RequestOptions {
  params?: Record<string, string | null | undefined>;
  query?: Record<string, string>;
  body?: unknown;
  key?: string;
  signal?: AbortSignal;
}

/**
 * Same-origin client for the Java product API. Only generated routes are callable,
 * request bodies are validated before sending and every response is validated before use.
 */
export class Api {
  readonly fetcher: Fetcher;
  csrf: string | null = null;

  constructor(fetcher?: Fetcher) {
    this.fetcher = fetcher ?? ((url, init) => globalThis.fetch(url, init));
  }

  async request<T = unknown>(
    method: "GET" | "POST",
    template: string,
    { params = {}, query, body, key, signal }: RequestOptions = {},
  ): Promise<T> {
    const route = routeTable[`${method} ${template}`];
    if (!route) throw new ApiError("UNKNOWN_OPERATION");
    if (route.request) validate(route.request, body);
    const path = template.replace(/\{([^}]+)\}/g, (_, name: string) =>
      encodeURIComponent(String(params[name])),
    );
    const headers: Record<string, string> = { Accept: "application/json" };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (method !== "GET" && template !== "/v1/bootstrap") {
      if (!this.csrf || !key) throw new ApiError("SESSION_NOT_READY");
      headers["X-CSRF-Token"] = this.csrf;
      headers["Idempotency-Key"] = key;
    }
    let response: Response;
    try {
      response = await this.fetcher(
        path + (query ? `?${new URLSearchParams(query)}` : ""),
        {
          method,
          credentials: "same-origin",
          cache: "no-store",
          headers,
          body: body === undefined ? undefined : JSON.stringify(body),
          signal: signal ?? AbortSignal.timeout(15000),
        },
      );
    } catch (error) {
      if (signal?.aborted) throw error;
      throw new ApiError("NETWORK_UNCERTAIN");
    }
    let value: unknown;
    try {
      value = await response.json();
    } catch {
      throw new ApiError("INVALID_RESPONSE");
    }
    const definition = route.responses[String(response.status)];
    if (!definition)
      throw new ApiError("UNEXPECTED_STATUS", null, response.status);
    validate(definition, value);
    if (!response.ok) {
      const error = value as ProductError;
      throw new ApiError(error.code, error, response.status);
    }
    if (template === "/v1/bootstrap")
      this.csrf = (value as ProductContract["BootstrapState"]).csrf_token;
    return value as T;
  }
}
