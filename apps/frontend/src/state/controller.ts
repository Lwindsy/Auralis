import { ApiError } from "../api/client.ts";
import type { RequestOptions } from "../api/client.ts";
import type {
  BootstrapState,
  DiscoveryInput,
  ProductState,
} from "../api/types.ts";
import { hasInput, needsPolling } from "./input.ts";

/** The subset of Api the controller depends on; tests substitute their own. */
export interface ApiLike {
  csrf: string | null;
  request<T = unknown>(
    method: "GET" | "POST",
    template: string,
    options?: RequestOptions,
  ): Promise<T>;
}

export interface PendingOperation {
  path: string;
  body: Record<string, unknown>;
  params: Record<string, string | null | undefined>;
  key: string;
  hideSlot: string | null;
}

export type CardActionKind = "HEARD" | "REPLACE";

/**
 * Page-side view of the authoritative Java state. It remembers one unconfirmed write so a
 * lost response can be retried with the same idempotency key, never as a second intent.
 * Reads never extend the retention period; only explicit user actions write.
 */
export class DiscoveryController {
  readonly api: ApiLike;
  state: ProductState | null = null;
  busy = false;
  pending: PendingOperation | null = null;
  error: Error | null = null;
  readonly hiddenSlots = new Set<string>();
  /** Increments on every visible change; React subscribes to it. */
  version = 0;
  private generation = 0;
  private polling = false;
  private timer: ReturnType<typeof setTimeout> | undefined;
  private disposed = false;
  private listeners = new Set<() => void>();

  constructor(
    api: ApiLike,
    onChange?: (controller: DiscoveryController) => void,
  ) {
    this.api = api;
    if (onChange) this.listeners.add(() => onChange(this));
  }

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  getVersion = () => this.version;

  private emit() {
    if (this.disposed) return;
    this.version++;
    for (const listener of this.listeners) listener();
  }

  private apply(state: ProductState) {
    if (
      this.state?.session_id === state.session_id &&
      state.revision < this.state.revision
    )
      return;
    this.state = state;
  }

  get locked() {
    return this.busy || !!this.pending;
  }

  async connect() {
    if (this.busy) return;
    this.busy = true;
    this.error = null;
    this.emit();
    const generation = ++this.generation;
    try {
      const result = await this.api.request<BootstrapState>(
        "POST",
        "/v1/bootstrap",
        { body: {} },
      );
      if (generation !== this.generation || this.disposed) return;
      this.apply(result.state);
      this.pending = null;
    } catch (error) {
      this.error = error as Error;
    } finally {
      this.busy = false;
      this.emit();
      this.schedule();
    }
  }

  async refresh() {
    if (this.polling || this.busy || this.disposed) return;
    const generation = this.generation;
    this.polling = true;
    try {
      const state = await this.api.request<ProductState>(
        "GET",
        "/v1/current-state",
      );
      if (generation !== this.generation || this.disposed) return;
      this.apply(state);
      // A lost write response stays pending until retried with its original key.
      if (!this.pending) this.error = null;
    } catch (error) {
      if (generation === this.generation) this.error = error as Error;
    } finally {
      this.polling = false;
      this.emit();
      this.schedule();
    }
  }

  async submit(input: DiscoveryInput) {
    if (!hasInput(input) || !this.state) return false;
    return this.mutate("/v1/discovery-sessions", {
      input: structuredClone(input),
      expected_session_id: this.state.session_id,
      expected_revision: this.state.revision,
    });
  }

  async action(
    operation: "card" | "next-batch" | "rescreen",
    slotId?: string,
    action?: CardActionKind,
  ) {
    if (!this.state) return false;
    const path =
      operation === "card"
        ? "/v1/discovery-sessions/{session_id}/cards/{slot_id}/actions"
        : `/v1/discovery-sessions/{session_id}/${operation}`;
    return this.mutate(
      path,
      { expected_revision: this.state.revision, ...(action ? { action } : {}) },
      { session_id: this.state.session_id, slot_id: slotId },
      slotId ?? null,
    );
  }

  private async mutate(
    path: string,
    body: Record<string, unknown>,
    params: Record<string, string | null | undefined> = {},
    hideSlot: string | null = null,
  ) {
    if (
      this.busy ||
      this.pending ||
      !this.state ||
      Date.parse(this.state.expires_at) <= Date.now()
    )
      return false;
    this.pending = { path, body, params, key: crypto.randomUUID(), hideSlot };
    return this.retry();
  }

  async retry() {
    if (!this.pending || this.busy) return false;
    const operation = this.pending;
    this.busy = true;
    this.error = null;
    ++this.generation;
    if (operation.hideSlot) this.hiddenSlots.add(operation.hideSlot);
    this.emit();
    try {
      const state = await this.api.request<ProductState>(
        "POST",
        operation.path,
        {
          params: operation.params,
          body: operation.body,
          key: operation.key,
        },
      );
      if (this.disposed) return false;
      this.apply(state);
      this.pending = null;
      return true;
    } catch (error) {
      this.error = error as Error;
      const details = error instanceof ApiError ? error.details : null;
      const disposition = details?.retry_disposition;
      if (disposition && disposition !== "RETRY_SAME_KEY") this.pending = null;
      if (details?.idempotency_consumed === false) this.hiddenSlots.clear();
      return false;
    } finally {
      this.busy = false;
      if (!this.pending) this.hiddenSlots.clear();
      this.emit();
      this.schedule();
    }
  }

  /** Abandon an unconfirmed write after the user chose to re-read state instead. */
  clearError() {
    this.error = null;
    this.emit();
  }

  schedule() {
    clearTimeout(this.timer);
    if (this.disposed || !this.state) return;
    const remaining = Date.parse(this.state.expires_at) - Date.now();
    if (remaining <= 0) {
      this.expire();
      return;
    }
    const delay = needsPolling(this.state)
      ? this.state.status === "RUNNING"
        ? 1000
        : 2000
      : remaining;
    this.timer = setTimeout(
      () => {
        if (!this.state || Date.parse(this.state.expires_at) <= Date.now())
          this.expire();
        else void this.refresh();
      },
      Math.min(delay, remaining, 2147483647),
    );
    (this.timer as { unref?: () => void }).unref?.();
  }

  expire() {
    ++this.generation;
    this.state = null;
    this.pending = null;
    this.hiddenSlots.clear();
    this.api.csrf = null;
    this.error = new Error("EXPIRED");
    this.emit();
    void this.connect();
  }

  dispose() {
    this.disposed = true;
    ++this.generation;
    clearTimeout(this.timer);
  }
}
