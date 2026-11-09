import type { Api } from "../../api/client.ts";
import { note, stateMessage } from "../../api/messages.ts";
import type { ProductCard, ProductState } from "../../api/types.ts";
import type { DiscoveryController } from "../../state/controller.ts";
import { useElapsed } from "../../state/hooks.ts";
import { Button } from "../Button.tsx";
import { Callout } from "../Callout.tsx";
import { Crate, type Tile } from "./Crate.tsx";
import { RequestSummary } from "./RequestSummary.tsx";

interface Props {
  api: Api;
  controller: DiscoveryController;
  state: ProductState;
  onEdit: () => void;
  onReview: (card: ProductCard) => void;
}

export function ResultsView({
  api,
  controller,
  state,
  onEdit,
  onReview,
}: Props) {
  const locked = controller.locked;
  const hidden = controller.hiddenSlots;
  const tiles: Tile[] = [];
  for (const slot of state.slots) {
    if (slot.status === "READY" && slot.card && !hidden.has(slot.slot_id))
      tiles.push({ kind: "card", slot, card: slot.card });
    else if (slot.status === "SUPPLYING" || hidden.has(slot.slot_id))
      tiles.push({ kind: "supply", slot });
  }
  const cards = tiles.filter((tile) => tile.kind === "card").length;
  const working = state.status === "RUNNING" || state.status === "WAITING";
  const status = stateMessage(state);
  const extra = note(state.message_key);
  const notes: string[] = [];
  if (extra && extra !== status) notes.push(extra);
  if (state.input_issues.length)
    notes.push("部分输入未能识别，请检查并修改需求。");
  if (state.supply_state === "PAUSED")
    notes.push("候选补充暂时暂停，并非候选已耗尽。");
  if (state.slots.some((slot) => slot.status === "FAILED"))
    notes.push("部分卡位补充失败，请稍后再试。");
  if (state.supply_state === "FINAL_EXHAUSTED")
    notes.push("本次可用候选已用尽，可以修改需求继续发现。");
  const uniqueNotes = [...new Set(notes)];
  const showEmpty = !working && tiles.length === 0;
  const basic = state.analysis_mode === "BASIC" && cards > 0;
  const curated = state.analysis_mode === "CURATED" && cards > 0;

  // Only say something when there is something to say: a full, normal group needs no status.
  const quiet = state.status === "SUCCEEDED" && !basic;
  const partial = cards > 0 && cards < state.recommendation_target_count;
  const topline = (
    <>
      <div className="topline__lead">
        <h2 id="results-title" className="visually-hidden">
          这次的发现
        </h2>
        {state.editable_input ? (
          <RequestSummary api={api} input={state.editable_input} />
        ) : null}
        <p
          className={quiet ? "visually-hidden" : "topline__status"}
          role="status"
        >
          {status}
        </p>
        {partial ? (
          <p className="topline__meta mono">
            已展示 {cards} 张 · 本组目标 {state.recommendation_target_count} 张
          </p>
        ) : null}
      </div>
      <div className="topline__actions">
        {basic ? (
          <Button
            variant="link"
            locked={locked}
            onClick={() => void controller.action("rescreen")}
          >
            按原需求重新复筛
          </Button>
        ) : null}
        {tiles.length || working ? (
          <Button
            variant="link"
            locked={locked}
            onClick={onEdit}
            data-key="edit"
          >
            修改需求
          </Button>
        ) : null}
        {cards ? (
          <Button
            variant="secondary"
            iconAfter="arrowRight"
            locked={locked}
            onClick={() => void controller.action("next-batch")}
            data-key="batch"
          >
            换一批
          </Button>
        ) : null}
      </div>
      {curated || uniqueNotes.length ? (
        <div className="topline__notes">
          {curated ? (
            <Callout tone="info">
              这一组来自编辑精选：没读到具体的音乐方向。
            </Callout>
          ) : null}
          {uniqueNotes.map((text) => (
            <Callout key={text}>{text}</Callout>
          ))}
        </div>
      ) : null}
    </>
  );

  return (
    <div className="results" aria-labelledby="results-title" role="region">
      {showEmpty ? (
        <section className="stage stage--night">
          <div className="stage__inner">
            <div className="stage__topline">{topline}</div>
            <div
              className="empty rise"
              role="group"
              aria-label="没有可展示的专辑"
            >
              <p className="empty__title">{status || "暂时没有可展示的专辑"}</p>
              <p className="empty__hint">
                {state.status === "NO_MATCH"
                  ? "补充一些喜欢的风格、专辑，或换一种描述方式，再试一次。"
                  : "可以调整需求后重新发起，原来的输入会保留在编辑面板里。"}
              </p>
              <Button
                variant="primary"
                size="lg"
                iconAfter="arrowRight"
                locked={locked}
                onClick={onEdit}
              >
                重新输入
              </Button>
            </div>
          </div>
        </section>
      ) : (
        <Crate
          key={state.round_id ?? "round"}
          tiles={tiles}
          locked={locked}
          placeholder={Math.min(state.recommendation_target_count, 5)}
          topline={topline}
          working={<Working state={state} />}
          onReview={onReview}
          onHeard={(slot) => void controller.action("card", slot, "HEARD")}
          onReplace={(slot) => void controller.action("card", slot, "REPLACE")}
        />
      )}
    </div>
  );
}

function Working({ state }: { state: ProductState }) {
  const waiting = state.status === "WAITING";
  const seconds = useElapsed(state.run_id ?? state.session_id, true);
  return (
    <div className="notes notes--working" aria-live="polite">
      <p className="notes__index mono">
        <span>{seconds}s</span>
      </p>
      <p className="notes__title">
        <span>{waiting ? "排队中" : "挑选中"}</span>
      </p>
      <p className="notes__review">
        {waiting ? "轮到你时会自动继续。" : "关掉页面也没关系，回来接着看。"}
      </p>
    </div>
  );
}
