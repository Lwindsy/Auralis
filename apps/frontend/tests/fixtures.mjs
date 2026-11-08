import fixtures from "../src/generated/fixtures.json" with { type: "json" };
import { validate } from "../src/api.js";
import { emptyInput } from "../src/state.js";

export const shared = (name) =>
  structuredClone(fixtures.find((row) => row.name === name).payload);
export function idle() {
  return {
    ...shared("fourth-request-waiting"),
    session_id: null,
    run_id: null,
    revision: 0,
    status: "IDLE",
    supply_state: "CLOSED",
    message_key: null,
    admission: null,
  };
}
export function card(number = 1) {
  return {
    candidate_id: `candidate-${number}`,
    identity: {
      ...shared("search-deduplicated-mbid").items[0].identity,
      title: `样例专辑 ${number}`,
      artist_credit: "合成测试艺人",
    },
    cover_url: null,
    short_review: {
      text: "这是用于交互验证的合成短评，不代表真实推荐结果。",
      citations: [{ marker: "R1", read_id: `read-${number}` }],
      invocation_id: "synthetic",
      prompt_version: "fixture",
    },
    long_review_state: "GENERATING",
  };
}
export function ready(count = 3, target = 3) {
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
      status: "READY",
      card: card(i + 1),
      message_key: null,
    })),
    supply_state: "SUFFICIENT",
    editable_input: { ...emptyInput(), request_text: "想听轻松的爵士" },
  };
}
export function review(candidateId, status = "READY") {
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
              production: "这是一篇合成的完整乐评，用于验证页面显示。",
              instrumentation: "",
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

// Test-only HTTP provider. No production import may reach this module.
export class FixtureBackend {
  constructor(state = idle()) {
    this.state = state;
    this.calls = [];
    this.receipts = new Map();
    this.heard = new Set();
    this.next = 10;
    this.reviewState = "GENERATING";
    this.reviewReads = 0;
    this.pendingSupply = false;
  }
  handle(method, rawUrl, body, headers = {}) {
    const url = new URL(rawUrl, "http://localhost");
    const path = url.pathname;
    this.calls.push({
      method,
      path,
      body: structuredClone(body),
      headers: { ...headers },
    });
    const send = (definition, payload, status = 200) => ({
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
            ? { ...slot, status: "READY", card: card(this.next++) }
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
    if (path === "/v1/catalog/genres")
      return send("GenreSearchResult", {
        reference_revision: "fixture",
        items: url.searchParams.has("parent_id")
          ? [
              {
                genre_id: "cool-jazz",
                name: "Cool Jazz",
                parent_ids: ["jazz"],
                has_children: false,
              },
            ]
          : [
              {
                genre_id: "jazz",
                name: "Jazz",
                parent_ids: [],
                has_children: true,
              },
              {
                genre_id: "metal",
                name: "Metal",
                parent_ids: [],
                has_children: false,
              },
            ],
        offset: 0,
        limit: 20,
        next_offset: null,
      });
    if (method === "POST") {
      if (
        headers["x-csrf-token"] !== "t".repeat(32) ||
        !headers["idempotency-key"]
      )
        throw new Error("Missing CSRF or idempotency header");
      const key = `${path}:${headers["idempotency-key"]}`;
      if (this.receipts.has(key))
        return structuredClone(this.receipts.get(key));
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
      let response;
      if (path.includes("/long-reviews/")) {
        const candidate = path.split("/").at(-2);
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
          );
          if (body.action === "HEARD") this.heard.add(slot.card.candidate_id);
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
        review(path.split("/").at(-1), this.reviewState),
      );
    }
    throw new Error(`Unsupported fixture ${method} ${path}`);
  }
}
