import { Api } from "./api.js";
import { DiscoveryController, stateMessage, message } from "./state.js";
import { el, button, safeImage } from "./dom.js";
import { Editor } from "./editor.js";
import { ReviewDialog } from "./review.js";

const api = new Api();
const results = document.querySelector("#results");
const notice = document.querySelector("#notice");
const recovery = document.querySelector("#recovery");
const controller = new DiscoveryController(api, render);
const review = new ReviewDialog(
  document.querySelector("#review"),
  api,
  () => controller.state,
  () => controller.refresh(),
);
const editor = new Editor(
  document.querySelector("#editor"),
  api,
  async (input) => {
    if (await controller.submit(input)) {
      editor.close();
      render();
    }
  },
  () => {
    editor.close();
    render();
    document.querySelector('[data-key="edit"]')?.focus();
  },
);
let connected = false;
let rendered = "";

function render() {
  const state = controller.state;
  editor.setLocked(controller.busy || !!controller.pending);
  notice.textContent = controller.error
    ? message(
        controller.error.details?.message_key,
        message(controller.error.message),
      )
    : state?.message_key === "session.expired"
      ? message(state.message_key)
      : "";
  recovery.replaceChildren();
  if (!state) {
    results.replaceChildren();
    rendered = "";
    review.close();
    if (connected) {
      editor.close();
      editor.root.replaceChildren();
    }
    if (controller.busy) recovery.append(el("p", {}, "正在恢复你的发现…"));
    else recovery.append(button("重新连接", () => controller.connect()));
    return;
  }
  if (controller.error) {
    recovery.append(
      button("刷新状态", () => controller.refresh(), {
        disabled: controller.busy,
      }),
    );
    if (controller.pending)
      recovery.append(
        button(
          "重试原操作",
          async () => {
            const submission =
              controller.pending.path === "/v1/discovery-sessions";
            if (await controller.retry()) {
              if (submission) editor.close();
              render();
            }
          },
          { disabled: controller.busy },
        ),
      );
  }
  if (!connected || (!state.session_id && editor.root.hidden)) {
    connected = true;
    if (!state.session_id) editor.open(state.editable_input, false);
  }
  const welcome = document.querySelector("#welcome");
  welcome.hidden = !state.restored;
  welcome.textContent =
    "欢迎回来！我们记住了你上次的选择，可以接着发现音乐，也可以随时修改 🎵";
  const signature = JSON.stringify([
    state,
    controller.busy,
    [...controller.hiddenSlots],
    !!controller.pending,
  ]);
  if (signature === rendered) return;
  rendered = signature;
  const focusKey = document.activeElement?.dataset.key;
  results.replaceChildren();
  if (!state.session_id) return;
  const disabled = controller.busy || !!controller.pending;
  const cards = state.slots.filter(
    (slot) =>
      slot.status === "READY" &&
      slot.card &&
      !controller.hiddenSlots.has(slot.slot_id),
  );
  results.append(
    el("h2", { id: "results-title" }, "这次的发现"),
    el("p", { role: "status" }, stateMessage(state)),
    el(
      "p",
      { className: "meta" },
      `已展示 ${cards.length} 张 · 本组目标 ${state.recommendation_target_count} 张`,
    ),
  );
  if (state.message_key)
    results.append(
      el(
        "p",
        { className: "note" },
        message(state.message_key, stateMessage(state)),
      ),
    );
  if (state.input_issues.length)
    results.append(
      el("p", { className: "note" }, "部分输入未能识别，请检查并修改需求。"),
    );
  const actions = el(
    "div",
    { className: "actions" },
    button(
      cards.length ? "修改需求" : "重新输入",
      () => editor.open(controller.state.editable_input, true),
      { disabled, "data-key": "edit" },
    ),
  );
  if (cards.length)
    actions.append(
      button("换一批", () => controller.action("next-batch"), {
        disabled,
        "data-key": "batch",
      }),
    );
  if (state.analysis_mode === "BASIC" && cards.length)
    actions.append(
      button("按原需求重新复筛", () => controller.action("rescreen"), {
        disabled,
        "data-key": "rescreen",
      }),
    );
  results.append(actions);
  const grid = el("div", { className: "cards" });
  for (const slot of cards) {
    const card = slot.card;
    const cover = el("div", { className: "cover" });
    const url = safeImage(card.cover_url);
    if (url)
      cover.append(
        el("img", {
          src: url,
          alt: `${card.identity.title} 封面`,
          loading: "lazy",
          onerror: () => cover.replaceChildren("暂无封面"),
        }),
      );
    else cover.append("暂无封面");
    grid.append(
      el(
        "article",
        { "aria-label": card.identity.title },
        cover,
        el("h3", {}, card.identity.title),
        el(
          "p",
          { className: "meta" },
          `${card.identity.artist_credit} · ${card.identity.first_release_year ?? "年份未知"}`,
        ),
        el(
          "p",
          { className: "meta" },
          card.identity.primary_genres.map((genre) => genre.name).join(" / "),
        ),
        el("p", {}, card.short_review.text),
        el(
          "div",
          { className: "actions" },
          button("详细乐评", () => review.open(card), {
            disabled,
            "data-key": `review-${slot.slot_id}`,
          }),
          button(
            "已听过",
            () => controller.action("card", slot.slot_id, "HEARD"),
            { disabled, "data-key": `heard-${slot.slot_id}` },
          ),
          button(
            "来张新的",
            () => controller.action("card", slot.slot_id, "REPLACE"),
            { disabled, "data-key": `replace-${slot.slot_id}` },
          ),
        ),
      ),
    );
  }
  results.append(grid);
  const supplying = state.slots.some(
    (slot) =>
      slot.status === "SUPPLYING" || controller.hiddenSlots.has(slot.slot_id),
  );
  if (supplying)
    results.append(
      el(
        "p",
        { className: "supply", role: "status" },
        "正在补充，其他专辑可以继续查看。",
      ),
    );
  if (state.supply_state === "PAUSED")
    results.append(
      el("p", { className: "note" }, "候选补充暂时暂停，并非候选已耗尽。"),
    );
  if (state.slots.some((slot) => slot.status === "FAILED"))
    results.append(
      el("p", { className: "note" }, "部分卡位补充失败，请稍后再试。"),
    );
  if (state.supply_state === "FINAL_EXHAUSTED")
    results.append(
      el(
        "p",
        { className: "meta" },
        "本次可用候选已用尽，可以修改需求继续发现。",
      ),
    );
  if (focusKey)
    [...results.querySelectorAll("[data-key]")]
      .find((node) => node.dataset.key === focusKey)
      ?.focus();
  review.reconcile(state);
}

document.addEventListener("visibilitychange", () => {
  if (!document.hidden && controller.state) void controller.refresh();
});
window.addEventListener("pagehide", () => {
  controller.dispose();
  review.close();
});
window.addEventListener("pageshow", (event) => {
  if (event.persisted) location.reload();
});
void controller.connect();
