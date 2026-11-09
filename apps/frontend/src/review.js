import { el, button } from "./dom.js";
import { message } from "./state.js";

const active = ["NOT_STARTED", "GENERATING", "NEEDS_MORE_RESEARCH"];
export class ReviewDialog {
  constructor(dialog, api, state, refresh) {
    this.dialog = dialog;
    this.api = api;
    this.state = state;
    this.refresh = refresh;
    this.body = dialog.querySelector("#review-body");
    this.epoch = 0;
    dialog.querySelector("#review-close").onclick = () => this.close();
    dialog.addEventListener("close", () => {
      ++this.epoch;
      clearTimeout(this.timer);
    });
  }
  close() {
    ++this.epoch;
    this.busy = false;
    clearTimeout(this.timer);
    if (this.dialog.open) this.dialog.close();
  }
  reconcile(state) {
    if (
      this.dialog.open &&
      (this.session !== state.session_id ||
        !state.slots.some(
          (slot) => slot.card?.candidate_id === this.card.candidate_id,
        ))
    )
      this.close();
  }
  async open(card) {
    this.close();
    this.card = card;
    this.session = this.state().session_id;
    this.pending = null;
    this.dialog.showModal();
    this.render({ status: card.long_review_state, review: null });
    await this.write("open");
  }
  async write(operation) {
    if (this.busy) return;
    this.pending ??= {
      operation,
      key: crypto.randomUUID(),
      body: { expected_revision: this.state().revision },
    };
    const epoch = this.epoch;
    this.busy = true;
    this.body.querySelectorAll("button").forEach((node) => {
      node.disabled = true;
    });
    try {
      const view = await this.api.request(
        "POST",
        `/v1/discovery-sessions/{session_id}/long-reviews/{candidate_id}/${this.pending.operation}`,
        {
          ...this.pending,
          params: {
            session_id: this.session,
            candidate_id: this.card.candidate_id,
          },
        },
      );
      if (epoch !== this.epoch) return;
      this.pending = null;
      await this.refresh();
      if (epoch !== this.epoch) return;
      this.render(view);
      this.schedule(view);
    } catch (error) {
      if (epoch !== this.epoch) return;
      const retry =
        !error.details || error.details.retry_disposition === "RETRY_SAME_KEY";
      if (!retry) this.pending = null;
      this.body.append(
        el("p", { role: "alert" }, message(error.message)),
        button(retry ? "重试原操作" : "刷新乐评状态", () =>
          retry ? this.write(operation) : this.read(),
        ),
      );
    } finally {
      if (epoch === this.epoch) this.busy = false;
    }
  }
  async read() {
    const epoch = this.epoch;
    try {
      const view = await this.api.request(
        "GET",
        "/v1/discovery-sessions/{session_id}/long-reviews/{candidate_id}",
        {
          params: {
            session_id: this.session,
            candidate_id: this.card.candidate_id,
          },
        },
      );
      if (epoch !== this.epoch || !this.dialog.open) return;
      this.render(view);
      this.schedule(view);
    } catch (error) {
      if (epoch !== this.epoch) return;
      this.body.append(
        el("p", { role: "alert" }, message(error.message)),
        button("刷新乐评状态", () => this.read()),
      );
    }
  }
  schedule(view) {
    clearTimeout(this.timer);
    if (active.includes(view.status) && this.dialog.open)
      this.timer = setTimeout(() => this.read(), 2000);
  }
  render(view) {
    this.body.replaceChildren(
      el(
        "h2",
        { id: "review-title" },
        `${this.card.identity.title} · 详细乐评`,
      ),
    );
    if (view.status === "READY" && view.review?.sections) {
      this.body.append(
        el(
          "div",
          { className: "review-text" },
          Object.values(view.review.sections).filter(Boolean).join("\n\n"),
        ),
      );
    } else if (active.includes(view.status) || view.status === "READY") {
      this.body.append(
        el("p", { role: "status" }, "详细乐评生成中"),
        ...["制作", "配器", "人声", "氛围"].map((name) =>
          el("div", { className: "placeholder" }, `${name} · 等待完整乐评`),
        ),
      );
    } else {
      const labels = {
        FAILED: "生成失败",
        TIMED_OUT: "生成超时",
        INTERRUPTED: "生成中断",
        CANCELLED: "生成已取消",
        BUDGET_EXHAUSTED: "本轮额度不足",
      };
      this.body.append(
        el("p", { role: "status" }, labels[view.status] ?? "乐评暂不可用"),
        button("重新生成", () => this.write("regenerate")),
      );
    }
  }
}
