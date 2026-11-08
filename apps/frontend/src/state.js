export const emptyInput = () => ({
  liked_genres: [],
  disliked_genre_ids: [],
  liked_albums: [],
  mode: "explore",
  request_text: "",
  language: "zh-CN",
});
export function hasInput(input) {
  return !!(
    input.liked_genres.length ||
    input.disliked_genre_ids.length ||
    input.liked_albums.length ||
    input.request_text.trim()
  );
}
export const needsPolling = (state) =>
  state &&
  (["RUNNING", "WAITING"].includes(state.status) ||
    state.supply_state === "SUPPLYING" ||
    state.slots.some(
      (slot) =>
        slot.status === "SUPPLYING" ||
        ["GENERATING", "NEEDS_MORE_RESEARCH"].includes(
          slot.card?.long_review_state,
        ),
    ));
export function stateMessage(state) {
  if (state.status === "WAITING")
    return "正在等待推荐名额，轮到你时会自动继续。";
  if (state.status === "RUNNING") return "正在寻找适合你的专辑…";
  if (state.analysis_mode === "BASIC")
    return "本次个性化未完成，先看看这些基础推荐。";
  return (
    {
      IDLE: "",
      SUCCEEDED: "这一组，准备好了。",
      PARTIAL: "本次找到的合格专辑不足目标数量，先听听这些。",
      NO_MATCH: "暂时没有合适的结果，试着补充更多信息。",
      FAILED: "这次推荐未能完成，请调整需求或稍后再试。",
      INTERRUPTED: "推荐已中断，已取得的结果仍可查看。",
    }[state.status] ?? ""
  );
}
const messages = {
  "session.expired": "上次发现已过期，已为你打开空白页面，可以重新开始。",
  "recommendation.waiting": "正在等待推荐名额，轮到你时会自动继续。",
  "recommendation.busy": "当前等待人数较多，请稍后重试。",
  "recommendation.queue_timeout": "本次等待已超时，你可以重新发起推荐。",
  "recommendation.provider_limited": "推荐服务暂时繁忙，请稍后再试。",
  QUEUE_FULL: "当前等待人数较多，请稍后重试。",
  QUEUE_TIMEOUT: "本次等待已超时，你可以重新发起推荐。",
  PROVIDER_RATE_LIMITED: "推荐服务暂时繁忙，请稍后再试。",
  EXECUTION_INTERRUPTED: "本次推荐已中断。",
  CATALOG_TIMEOUT: "目录搜索超时，请重试；这不代表没有匹配的专辑。",
  NETWORK_UNCERTAIN:
    "连接暂时中断，操作是否完成尚不确定。请先刷新状态，或用原操作重试。",
  INVALID_RESPONSE: "服务返回了无法识别的数据，请稍后刷新状态。",
  SESSION_NOT_READY: "会话尚未连接，请重新连接。",
  UNEXPECTED_STATUS: "服务暂时不可用，请稍后刷新状态。",
  REVISION_CONFLICT: "结果已在另一页面更新，请刷新状态后再操作。",
  EXPIRED: "上次发现已过期，请重新连接后开始。",
};
// Only reviewed local copy is displayed. Unknown message keys never echo server text.
export function message(
  key,
  fallback = "这次操作未能完成，请刷新状态后重试。",
) {
  return messages[key] ?? fallback;
}

export class DiscoveryController {
  constructor(api, onChange = () => {}) {
    this.api = api;
    this.onChange = onChange;
    this.state = null;
    this.busy = false;
    this.pending = null;
    this.error = null;
    this.hiddenSlots = new Set();
    this.generation = 0;
    this.polling = false;
    this.timer = null;
    this.disposed = false;
  }
  emit() {
    if (!this.disposed) this.onChange(this);
  }
  apply(state) {
    if (
      this.state?.session_id === state.session_id &&
      state.revision < this.state.revision
    )
      return;
    this.state = state;
  }
  async connect() {
    if (this.busy) return;
    this.busy = true;
    this.error = null;
    this.emit();
    const generation = ++this.generation;
    try {
      const result = await this.api.request("POST", "/v1/bootstrap", {
        body: {},
      });
      if (generation !== this.generation || this.disposed) return;
      this.apply(result.state);
      this.pending = null;
    } catch (error) {
      this.error = error;
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
      const state = await this.api.request("GET", "/v1/current-state");
      if (generation !== this.generation || this.disposed) return;
      this.apply(state);
      // A lost write response stays pending until retried with its original key.
      if (!this.pending) this.error = null;
    } catch (error) {
      if (generation === this.generation) this.error = error;
    } finally {
      this.polling = false;
      this.emit();
      this.schedule();
    }
  }
  async submit(input) {
    if (!hasInput(input)) return false;
    return this.mutate("/v1/discovery-sessions", {
      input: structuredClone(input),
      expected_session_id: this.state.session_id,
      expected_revision: this.state.revision,
    });
  }
  async action(operation, slotId, action) {
    const path =
      operation === "card"
        ? "/v1/discovery-sessions/{session_id}/cards/{slot_id}/actions"
        : `/v1/discovery-sessions/{session_id}/${operation}`;
    return this.mutate(
      path,
      { expected_revision: this.state.revision, ...(action ? { action } : {}) },
      { session_id: this.state.session_id, slot_id: slotId },
      slotId,
    );
  }
  async mutate(path, body, params = {}, hideSlot = null) {
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
      const state = await this.api.request("POST", operation.path, operation);
      if (this.disposed) return false;
      this.apply(state);
      this.pending = null;
      return true;
    } catch (error) {
      this.error = error;
      const disposition = error.details?.retry_disposition;
      if (disposition && disposition !== "RETRY_SAME_KEY") this.pending = null;
      if (error.details?.idempotency_consumed === false)
        this.hiddenSlots.clear();
      return false;
    } finally {
      this.busy = false;
      if (!this.pending) this.hiddenSlots.clear();
      this.emit();
      this.schedule();
    }
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
        if (Date.parse(this.state.expires_at) <= Date.now()) this.expire();
        else void this.refresh();
      },
      Math.min(delay, remaining, 2147483647),
    );
    this.timer.unref?.();
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
