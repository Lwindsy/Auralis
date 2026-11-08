import validators from "./generated/validators.cjs";
import routes from "./generated/routes.json" with { type: "json" };

export class ApiError extends Error {
  constructor(code, details = null, status = 0) {
    super(code);
    this.details = details;
    this.status = status;
  }
}

export function validate(name, value) {
  if (!validators[name]?.(value)) throw new ApiError("INVALID_RESPONSE");
  return value;
}

export class Api {
  constructor(fetcher = globalThis.fetch.bind(globalThis)) {
    this.fetcher = fetcher;
    this.csrf = null;
  }

  async request(
    method,
    template,
    { params = {}, query, body, key, signal } = {},
  ) {
    const route = routes[`${method} ${template}`];
    if (!route) throw new ApiError("UNKNOWN_OPERATION");
    if (route.request) validate(route.request, body);
    const path = template.replace(/\{([^}]+)\}/g, (_, name) =>
      encodeURIComponent(params[name]),
    );
    const headers = { Accept: "application/json" };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (method !== "GET" && template !== "/v1/bootstrap") {
      if (!this.csrf || !key) throw new ApiError("SESSION_NOT_READY");
      headers["X-CSRF-Token"] = this.csrf;
      headers["Idempotency-Key"] = key;
    }
    let response;
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
    let value;
    try {
      value = await response.json();
    } catch {
      throw new ApiError("INVALID_RESPONSE");
    }
    const definition = route.responses[String(response.status)];
    if (!definition)
      throw new ApiError("UNEXPECTED_STATUS", null, response.status);
    validate(definition, value);
    if (!response.ok) throw new ApiError(value.code, value, response.status);
    if (template === "/v1/bootstrap") this.csrf = value.csrf_token;
    return value;
  }
}
