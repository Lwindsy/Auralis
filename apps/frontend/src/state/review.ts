import { ApiError } from "../api/client.ts";
import type {
  LongReviewStatus,
  LongReviewView,
  ProductCard,
  ProductState,
} from "../api/types.ts";
import type { ApiLike } from "./controller.ts";

export const ACTIVE_REVIEW: LongReviewStatus[] = [
  "NOT_STARTED",
  "GENERATING",
  "NEEDS_MORE_RESEARCH",
];

export interface ReviewError {
  code: string;
  messageKey: string | null;
  /** True when the same write may be retried with its original idempotency key. */
  retry: boolean;
}

interface PendingWrite {
  operation: "open" | "regenerate";
  key: string;
  body: { expected_revision: number };
}

type ReviewViewState = Pick<
  LongReviewView,
  "status" | "review" | "message_key"
>;

/**
 * Long-review panel logic. POST open marks an explicit user open and raises priority; GET
 * only reads. Failed, timed-out or interrupted reviews are regenerated only on request.
 */
export class LongReviewController {
  card: ProductCard | null = null;
  session: string | null = null;
  view: ReviewViewState | null = null;
  error: ReviewError | null = null;
  busy = false;
  isOpen = false;
  version = 0;
  pending: PendingWrite | null = null;
  epoch = 0;
  private timer: ReturnType<typeof setTimeout> | undefined;
  private listeners = new Set<() => void>();
  private readonly api: ApiLike;
  private readonly state: () => ProductState | null;
  private readonly refresh: () => Promise<void>;

  constructor(
    api: ApiLike,
    state: () => ProductState | null,
    refresh: () => Promise<void>,
  ) {
    this.api = api;
    this.state = state;
    this.refresh = refresh;
  }

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  getVersion = () => this.version;

  protected emit() {
    this.version++;
    for (const listener of this.listeners) listener();
  }

  close() {
    ++this.epoch;
    this.busy = false;
    clearTimeout(this.timer);
    if (this.isOpen) {
      this.isOpen = false;
      this.emit();
    }
  }

  /**
   * Closes the panel when its session or card no longer exists in the current state.
   * `retained` lists cards the page still shows although they left their slot (albums
   * marked 听过了 stay in the room, and their long review stays readable).
   */
  reconcile(
    state: ProductState | null,
    retained: ReadonlySet<string> = new Set(),
  ) {
    if (!this.isOpen || !this.card) return;
    const card = this.card;
    if (
      !state ||
      this.session !== state.session_id ||
      (!retained.has(card.candidate_id) &&
        !state.slots.some(
          (slot) => slot.card?.candidate_id === card.candidate_id,
        ))
    )
      this.close();
  }

  async open(card: ProductCard) {
    this.close();
    const state = this.state();
    if (!state) return;
    this.card = card;
    this.session = state.session_id;
    this.pending = null;
    this.error = null;
    this.isOpen = true;
    this.view = {
      status: card.long_review_state,
      review: null,
      message_key: null,
    };
    this.emit();
    await this.write("open");
  }

  async write(operation: "open" | "regenerate") {
    if (this.busy || !this.card) return;
    const state = this.state();
    this.pending ??= {
      operation,
      key: crypto.randomUUID(),
      body: { expected_revision: state?.revision ?? 0 },
    };
    const epoch = this.epoch;
    this.busy = true;
    this.error = null;
    this.emit();
    try {
      const view = await this.api.request<LongReviewView>(
        "POST",
        `/v1/discovery-sessions/{session_id}/long-reviews/{candidate_id}/${this.pending.operation}`,
        {
          body: this.pending.body,
          key: this.pending.key,
          params: {
            session_id: this.session,
            candidate_id: this.card.candidate_id,
          },
        },
      );
      if (epoch !== this.epoch) return;
      this.pending = null;
      // The interaction changed the session revision; wait for it before the next write.
      await this.refresh();
      if (epoch !== this.epoch) return;
      this.setView(view);
    } catch (error) {
      if (epoch !== this.epoch) return;
      const details = error instanceof ApiError ? error.details : null;
      const retry = !details || details.retry_disposition === "RETRY_SAME_KEY";
      if (!retry) this.pending = null;
      this.error = {
        code: (error as Error).message,
        messageKey: details?.message_key ?? null,
        retry,
      };
    } finally {
      if (epoch === this.epoch) {
        this.busy = false;
        this.emit();
      }
    }
  }

  async read() {
    if (!this.card) return;
    const epoch = this.epoch;
    try {
      const view = await this.api.request<LongReviewView>(
        "GET",
        "/v1/discovery-sessions/{session_id}/long-reviews/{candidate_id}",
        {
          params: {
            session_id: this.session,
            candidate_id: this.card.candidate_id,
          },
        },
      );
      if (epoch !== this.epoch || !this.isOpen) return;
      this.error = null;
      this.setView(view);
    } catch (error) {
      if (epoch !== this.epoch) return;
      const details = error instanceof ApiError ? error.details : null;
      this.error = {
        code: (error as Error).message,
        messageKey: details?.message_key ?? null,
        retry: false,
      };
    }
    this.emit();
  }

  protected setView(view: ReviewViewState) {
    this.view = view;
    this.schedule(view);
  }

  protected schedule(view: ReviewViewState) {
    clearTimeout(this.timer);
    if (ACTIVE_REVIEW.includes(view.status) && this.isOpen)
      this.timer = setTimeout(() => void this.read(), 2000);
  }

  dispose() {
    this.close();
    this.listeners.clear();
  }
}
