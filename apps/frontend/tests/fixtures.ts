// Test-only synthetic provider. No production import may reach this module.
import fixtures from "../src/generated/fixtures.json" with { type: "json" };
import { validate, type DefinitionName } from "../src/api/client.ts";
import type {
  AlbumSearchResult,
  ProductCard,
  ProductState,
  LongReviewView,
  LongReviewStatus,
} from "../src/api/types.ts";
import { emptyInput } from "../src/state/input.ts";

interface FixtureRow {
  name: string;
  definition: string;
  structural_valid: boolean;
  payload: unknown;
}

const rows = fixtures as FixtureRow[];

export const shared = <T = unknown>(name: string): T =>
  structuredClone(rows.find((row) => row.name === name)!.payload) as T;

export function idle(): ProductState {
  return {
    ...shared<ProductState>("fourth-request-waiting"),
    session_id: null,
    run_id: null,
    revision: 0,
    status: "IDLE",
    supply_state: "CLOSED",
    message_key: null,
    admission: null,
  };
}

export function card(number = 1): ProductCard {
  return {
    candidate_id: `candidate-${number}`,
    identity: {
      ...shared<AlbumSearchResult>("search-deduplicated-mbid").items[0]!
        .identity,
      title: `样例专辑 ${number}`,
      artist_credit: "合成测试艺人",
    },
    cover_url: null,
    short_review: {
      text: "这是用于交互验证的合成短评，不代表真实推荐结果。[R1]",
      citations: [{ marker: "R1", read_id: `read-${number}` }],
      invocation_id: "synthetic",
      prompt_version: "fixture",
    },
    long_review_state: "GENERATING",
  };
}

export function ready(count = 3, target = 3): ProductState {
  return {
    ...idle(),
    session_id: "session-1",
    round_id: "round-1",
    run_id: "run-1",
    revision: 1,
    status: count ? (count < target ? "PARTIAL" : "SUCCEEDED") : "NO_MATCH",
    analysis_mode: "PERSONALIZED",
    recommendation_target_count: target,
    slots: Array.from({ length: count }, (_, i) => ({
      slot_id: `slot-${i + 1}`,
      status: "READY" as const,
      card: card(i + 1),
      message_key: null,
    })),
    supply_state: "SUFFICIENT",
    editable_input: { ...emptyInput(), request_text: "想听轻松的爵士" },
  };
}

export function review(
  candidateId: string,
  status: LongReviewStatus = "READY",
): LongReviewView {
  return {
    candidate_id: candidateId,
    status,
    message_key: null,
    review:
      status === "READY"
        ? {
            candidate_id: candidateId,
            status: "READY",
            sections: {
              production: "这是一篇合成的完整乐评，用于验证页面显示。[R1]",
              instrumentation: "第二段合成文字，验证多段落排版。",
              vocals: "",
              mood: "",
            },
            citations: [{ marker: "R1", read_id: "read-1" }],
            han_char_count: 25,
            extra_read_ids: [],
            missing_sections: [],
            invocation_ids: ["synthetic"],
            prompt_version: "fixture",
            reason_code: null,
          }
        : null,
  };
}

export interface FixtureCall {
  method: string;
  path: string;
  body: unknown;
  headers: Record<string, string>;
}

export interface FixtureResponse {
  status: number;
  body: unknown;
}

const GENRES = {
  roots: [
    { genre_id: "jazz", name: "Jazz", parent_ids: [], has_children: true },
    { genre_id: "metal", name: "Metal", parent_ids: [], has_children: false },
    {
      genre_id: "electronic",
      name: "Electronic",
      parent_ids: [],
      has_children: true,
    },
  ],
  children: {
    jazz: [
      {
        genre_id: "cool-jazz",
        name: "Cool Jazz",
        parent_ids: ["jazz"],
        has_children: false,
      },
      {
        genre_id: "modal-jazz",
        name: "Modal Jazz",
        parent_ids: ["jazz"],
        has_children: false,
      },
    ],
    electronic: [
      {
        genre_id: "ambient",
        name: "Ambient",
        parent_ids: ["electronic"],
        has_children: false,
      },
    ],
  } as Record<string, unknown[]>,
};

/** Synthetic HTTP backend for fixture mode and browser tests. */
export class FixtureBackend {
  state: ProductState;
  calls: FixtureCall[] = [];
  receipts = new Map<string, FixtureResponse>();
  heard = new Set<string>();
  next = 10;
  reviewState: LongReviewStatus = "GENERATING";
  reviewReads = 0;
  pendingSupply = false;

  constructor(state: ProductState = idle()) {
    this.state = state;
  }

  handle(
    method: string,
    rawUrl: string,
    body?: any, // eslint-disable-line @typescript-eslint/no-explicit-any
    headers: Record<string, string> = {},
  ): FixtureResponse {
    const url = new URL(rawUrl, "http://localhost");
    const path = url.pathname;
    this.calls.push({
      method,
      path,
      body: structuredClone(body),
      headers: { ...headers },
    });
    const send = (
      definition: DefinitionName,
      payload: unknown,
      status = 200,
    ) => ({
      status,
      body: validate(definition, structuredClone(payload)),
    });
    if (path === "/v1/bootstrap")
      return send("BootstrapState", {
        csrf_token: "t".repeat(32),
        state: { ...this.state, restored: !!this.state.session_id },
      });
    if (
      method === "GET" &&
      (path === "/v1/current-state" ||
        /^\/v1\/discovery-sessions\/[^/]+$/.test(path))
    ) {
      if (this.state.status === "RUNNING") {
        this.state = {
          ...ready(),
          editable_input: this.state.editable_input,
          revision: this.state.revision + 1,
        };
      }
      if (this.pendingSupply) {
        this.state.slots = this.state.slots.map((slot) =>
          slot.status === "SUPPLYING"
            ? { ...slot, status: "READY" as const, card: card(this.next++) }
            : slot,
        );
        this.pendingSupply = false;
        this.state.revision++;
        this.state.supply_state = "SUFFICIENT";
      }
      return send("ProductState", this.state);
    }
    if (path.startsWith("/v1/catalog/albums/"))
      return send("ResolvedAlbum", shared("outside-core-genre-mapping"));
    if (path === "/v1/catalog/albums")
      return send("AlbumSearchResult", shared("search-deduplicated-mbid"));
    if (path === "/v1/catalog/genres") {
      const parent = url.searchParams.get("parent_id");
      const query = url.searchParams.get("query")?.toLowerCase();
      let items: unknown[] = parent
        ? (GENRES.children[parent] ?? [])
        : GENRES.roots;
      if (query)
        items = [
          ...GENRES.roots,
          ...Object.values(GENRES.children).flat(),
        ].filter((node) =>
          (node as { name: string }).name.toLowerCase().includes(query),
        );
      return send("GenreSearchResult", {
        reference_revision: "fixture",
        items,
        offset: 0,
        limit: 50,
        next_offset: null,
      });
    }
    if (method === "POST") {
      if (
        headers["x-csrf-token"] !== "t".repeat(32) ||
        !headers["idempotency-key"]
      )
        throw new Error("Missing CSRF or idempotency header");
      const key = `${path}:${headers["idempotency-key"]}`;
      const receipt = this.receipts.get(key);
      if (receipt) return structuredClone(receipt);
      if (body.expected_revision !== this.state.revision)
        return send(
          "ProductError",
          {
            code: "REVISION_CONFLICT",
            request_id: "fixture",
            message_key: "revision.conflict",
            retryable: false,
            idempotency_consumed: false,
            current_revision: this.state.revision,
            retry_disposition: "POLL_STATE",
          },
          409,
        );
      let response: FixtureResponse;
      if (path.includes("/long-reviews/")) {
        const candidate = path.split("/").at(-2)!;
        if (path.endsWith("/regenerate")) {
          this.reviewState = "GENERATING";
          this.reviewReads = 0;
        }
        response = send("LongReviewView", review(candidate, this.reviewState));
      } else {
        if (path === "/v1/discovery-sessions") {
          validate("DiscoverySubmission", body);
          this.state = {
            ...ready(),
            status: "RUNNING",
            slots: [],
            supply_state: "SUPPLYING",
            editable_input: structuredClone(body.input),
            revision: this.state.revision + 1,
          };
        } else if (path.endsWith("/actions")) {
          const slot = this.state.slots.find(
            (entry) => entry.slot_id === path.split("/").at(-2),
          )!;
          if (body.action === "HEARD") this.heard.add(slot.card!.candidate_id);
          slot.card = null;
          slot.status = "SUPPLYING";
          this.pendingSupply = true;
          this.state.supply_state = "SUPPLYING";
          this.state.revision++;
        } else if (path.endsWith("/next-batch") || path.endsWith("/rescreen")) {
          this.state.slots = this.state.slots.map((slot) => ({
            ...slot,
            card: card(this.next++),
          }));
          this.state.analysis_mode = "PERSONALIZED";
          this.state.revision++;
        } else throw new Error(`Unsupported fixture operation ${path}`);
        response = send("ProductState", this.state);
      }
      this.receipts.set(key, structuredClone(response));
      return response;
    }
    if (path.includes("/long-reviews/")) {
      if (++this.reviewReads > 1 && this.reviewState === "GENERATING")
        this.reviewState = "READY";
      return send(
        "LongReviewView",
        review(path.split("/").at(-1)!, this.reviewState),
      );
    }
    throw new Error(`Unsupported fixture ${method} ${path}`);
  }
}
