import { ApiError } from "../api/client.ts";
import type { RequestOptions } from "../api/client.ts";
import type {
  BootstrapState,
  DiscoveryInput,
  ProductState,
} from "../api/types.ts";
import { awaitingContinuation, hasInput, needsPolling } from "./input.ts";

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
  /** Undo offer after success: the server replaces or clears its record with every write. */
  undo: UndoableAction | null;
}

export type CardActionKind = "HEARD" | "REPLACE";

/** The last single-card action of this page, which the server can still undo. */
export interface UndoableAction {
  sessionId: string;
  slotId: string;
  candidateId: string;
  action: CardActionKind;
}

const SUBMIT_PATH = "/v1/discovery-sessions";
const UNDO_PATH = "/v1/discovery-sessions/{session_id}/undo-last-card";
/** How long an exhausted round is watched for the server's automatic next round. */
const CONTINUATION_GRACE_MS = 15000;

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
  /**
   * The product state has no undo flag, so this only covers actions made in this page;
   * after a reload the server record still exists but the page does not offer it.
   */
  undoable: UndoableAction | null = null;
  private lastWriteAt = 0;
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
    if (this.undoable && this.undoable.sessionId !== state.session_id)
      this.undoable = null;
    this.state = state;
  }

  get locked() {
    return this.busy || !!this.pending;
  }

  /** True while an exhausted round may still be continued by the server. */
  get continuing() {
    return (
      Date.now() - this.lastWriteAt < CONTINUATION_GRACE_MS &&
      awaitingContinuation(this.state)
    );
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
    return this.mutate(SUBMIT_PATH, {
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
    const sessionId = this.state.session_id;
    const candidateId = this.state.slots.find((slot) => slot.slot_id === slotId)
      ?.card?.candidate_id;
    // The server keeps one undo record: a new card action replaces it, a batch or a
    // rescreen clears it.
    const undo =
      operation === "card" && action && slotId && sessionId && candidateId
        ? { sessionId, slotId, candidateId, action }
        : null;
    return this.mutate(
      path,
      { expected_revision: this.state.revision, ...(action ? { action } : {}) },
      { session_id: sessionId, slot_id: slotId },
      slotId ?? null,
      undo,
    );
  }

  async continueAfter(candidateId: string) {
    const state = this.state;
    if (!state || state.continuation_tail_id !== candidateId) return false;
    return this.mutate(
      "/v1/discovery-sessions/{session_id}/next-batch",
      { expected_revision: state.revision, tail_candidate_id: candidateId },
      { session_id: state.session_id },
      null,
      this.undoable,
    );
  }

  /** Restores the card and the 已听过 mark changed by the last single-card action. */
  async undo() {
    const target = this.undoable;
    if (!this.state || !target || target.sessionId !== this.state.session_id)
      return false;
    return this.mutate(
      UNDO_PATH,
      { expected_revision: this.state.revision },
      { session_id: target.sessionId },
    );
  }

  private async mutate(
    path: string,
    body: Record<string, unknown>,
    params: Record<string, string | null | undefined> = {},
    hideSlot: string | null = null,
    undo: UndoableAction | null = null,
  ) {
    if (
      this.busy ||
      this.pending ||
      !this.state ||
      Date.parse(this.state.expires_at) <= Date.now()
    )
      return false;
    this.pending = {
      path,
      body,
      params,
      key: crypto.randomUUID(),
      hideSlot,
      undo,
    };
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
      this.lastWriteAt = Date.now();
      this.undoable = operation.undo;
      return true;
    } catch (error) {
      this.error = error as Error;
      const details = error instanceof ApiError ? error.details : null;
      const disposition = details?.retry_disposition;
      if (
        operation.path === UNDO_PATH &&
        details?.code === "RESOURCE_UNAVAILABLE"
      ) {
        // Nothing left to undo on the server (already undone, or a batch cleared it).
        this.undoable = null;
        this.error = new ApiError("UNDO_UNAVAILABLE", null, 409);
        this.pending = null;
      }
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
    const delay =
      needsPolling(this.state) || this.continuing
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
    this.undoable = null;
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
