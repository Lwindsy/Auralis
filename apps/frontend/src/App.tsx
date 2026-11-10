import { useCallback, useEffect, useRef, useState } from "react";
import type { Api } from "./api/client.ts";
import { ApiError } from "./api/client.ts";
import { message } from "./api/messages.ts";
import type { DiscoveryInput, ProductState } from "./api/types.ts";
import { Deck, type Feeling } from "./components/deck/Deck.tsx";
import { Entry } from "./components/entry/Entry.tsx";
import { Icon } from "./components/Icon.tsx";
import { Drum } from "./components/motion/Drum.tsx";
import {
  Contours,
  ripple,
  setFlow,
  setTerrain,
} from "./components/motion/Contours.tsx";
import { Waiting } from "./components/motion/Waiting.tsx";
import { paintPage, withViewTransition } from "./lib/motion.ts";
import type { DiscoveryController } from "./state/controller.ts";
import { readyCards } from "./state/deck.ts";
import { DraftStore, type EntryDraft } from "./state/draft.ts";
import { useStore } from "./state/hooks.ts";
import { supplying } from "./state/input.ts";
import type { LongReviewController } from "./state/review.ts";

interface AppProps {
  api: Api;
  controller: DiscoveryController;
  review: LongReviewController;
  fixture: boolean;
}

type Stage = "entry" | "leaving" | "waiting" | "deck";

/** Time the start page takes to be cut away before the wait begins. */
const LEAVE_MS = 720;
/** Shortest wait shown, so even an instant answer winds down instead of flashing. */
const MIN_WAIT_MS = 1600;
/** The start page and the wait: a deep night-blue room lit warm from one corner. */
const NIGHT = { room: "#0d1220", ink: "#efe9df" };

const working = (state: ProductState) =>
  ["RUNNING", "WAITING"].includes(state.status);

/** The request has an answer to show: cards, or a final outcome with nothing coming. */
const answered = (state: ProductState) =>
  !working(state) && (readyCards(state).length > 0 || !supplying(state));

interface WaitMark {
  session: string | null;
  revision: number;
}

export function App({ api, controller, review, fixture }: AppProps) {
  useStore(controller);
  const state = controller.state;
  const [requested, setRequested] = useState<Stage | null>(null);
  const [waitSince, setWaitSince] = useState(() => Date.now());
  const [waitMark, setWaitMark] = useState<WaitMark | null>(null);
  const [minDone, setMinDone] = useState(true);
  const [epoch, setEpoch] = useState(0);
  const [entryKey, setEntryKey] = useState(0);
  const [drafts] = useState(() => new DraftStore());
  const [feelings, setFeelings] = useState<ReadonlyMap<string, Feeling>>(
    () => new Map(),
  );
  const [room, setRoom] = useState(NIGHT);
  const minTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => clearTimeout(minTimer.current), []);

  const session = state?.session_id ?? null;
  let stage: Stage | "connecting";
  if (!state) stage = "connecting";
  else if (!session) stage = requested === "leaving" ? "leaving" : "entry";
  else if (requested) stage = requested;
  else stage = working(state) && !readyCards(state).length ? "waiting" : "deck";
  // A restored request that is still being worked on: hold the wait explicitly so it
  // winds down into the albums instead of cutting over when they arrive.
  if (stage === "waiting" && requested !== "waiting") setRequested("waiting");

  const changed =
    !waitMark ||
    waitMark.session !== session ||
    (state?.revision ?? 0) > waitMark.revision;
  const settling =
    stage === "waiting" &&
    !!state &&
    minDone &&
    changed &&
    !controller.pending &&
    !controller.busy &&
    answered(state);

  useEffect(() => {
    review.reconcile(state);
  });

  const deckRoom = stage === "deck" ? room : NIGHT;
  useEffect(() => {
    paintPage(deckRoom.room, deckRoom.ink);
  }, [deckRoom.room, deckRoom.ink]);
  useEffect(() => {
    setFlow(stage === "waiting" ? 2.4 : stage === "deck" ? 0.45 : 0.7);
  }, [stage]);
  // Away from the start page the land keeps the shape of the submitted mode.
  const submittedMode = state?.editable_input?.mode ?? "explore";
  useEffect(() => {
    if (stage !== "entry" && stage !== "leaving") setTerrain(submittedMode);
  }, [stage, submittedMode]);

  const paint = useCallback(
    (base: string, ink: string) => setRoom({ room: base, ink }),
    [],
  );

  const startWait = (mark: WaitMark | null) => {
    setWaitMark(mark);
    setWaitSince(Date.now());
    setMinDone(false);
    clearTimeout(minTimer.current);
    minTimer.current = setTimeout(() => setMinDone(true), MIN_WAIT_MS);
    setRequested("waiting");
    ripple(1.4);
  };

  const owner = session ?? "blank";
  const keepDraft = (draft: EntryDraft) => drafts.keep(owner, draft);

  const submit = async (input: DiscoveryInput) => {
    if (!state) return;
    const mark = { session, revision: state.revision };
    setRequested("leaving");
    const leave = new Promise((resolve) => setTimeout(resolve, LEAVE_MS));
    const ok = await controller.submit(input);
    await leave;
    if (ok || controller.pending) {
      drafts.discard();
      startWait(mark);
    } else setRequested("entry");
  };

  const more = async () => {
    if (!state) return;
    startWait({ session, revision: state.revision });
    let ok = await controller.action("next-batch");
    if (!ok && !controller.pending && controller.state?.editable_input) {
      // Nothing left to swap in place: ask again with the same input.
      controller.clearError();
      ok = await controller.submit(controller.state.editable_input);
    }
    if (!ok && !controller.pending) setRequested("deck");
  };

  const toEntry = (prefill: boolean) =>
    withViewTransition(() => {
      review.close();
      if (prefill) {
        drafts.discard();
        setEntryKey((value) => value + 1);
      }
      setRequested("entry");
    });

  const settled = () => {
    review.close();
    setEpoch((value) => value + 1);
    setRequested("deck");
    ripple(1.6);
  };

  const error = controller.error;
  const errorText = error
    ? message(
        error instanceof ApiError ? error.details?.message_key : null,
        message(error.message),
      )
    : null;

  const canResume =
    !!state && !!session && readyCards(state).length > 0 && stage === "entry";

  return (
    <>
      <Contours />
      {fixture ? (
        <p className="fixture-banner">
          契约样例模式 · 合成数据，不是真实推荐服务
        </p>
      ) : null}
      <a
        className="brand"
        data-hidden={stage === "entry" || stage === "leaving" || undefined}
        aria-hidden={stage === "entry" || stage === "leaving" || undefined}
        tabIndex={stage === "entry" || stage === "leaving" ? -1 : undefined}
        href="/"
        aria-label="Auralis 首页"
        onClick={(event) => {
          if (!state || event.metaKey || event.ctrlKey) return;
          event.preventDefault();
          if (stage === "deck") toEntry(false);
        }}
      >
        <span className="brand__mark" aria-hidden="true" />
        <span className="brand__name">Auralis</span>
      </a>

      <main className="stage" data-stage={stage}>
        {stage === "connecting" ? (
          <section className="connecting">
            {controller.busy ? (
              <>
                <Drum settling={false} />
                <p role="status">正在恢复你的发现…</p>
              </>
            ) : (
              <>
                <p>暂时无法连接到 Auralis。</p>
                <button
                  type="button"
                  className="text-link"
                  onClick={() => void controller.connect()}
                >
                  重新连接
                </button>
              </>
            )}
          </section>
        ) : stage === "entry" || stage === "leaving" ? (
          <Entry
            key={`${owner}:${entryKey}`}
            api={api}
            initial={state!.editable_input}
            saved={drafts.get(owner)}
            onDraft={keepDraft}
            locked={controller.locked}
            leaving={stage === "leaving"}
            notice={
              state!.message_key === "session.expired" && !session ? (
                <p className="entry__notice" role="status">
                  {message("session.expired")}
                </p>
              ) : null
            }
            onResume={
              canResume
                ? () => withViewTransition(() => setRequested("deck"))
                : null
            }
            onSubmit={(input) => void submit(input)}
          />
        ) : stage === "waiting" ? (
          <Waiting
            state={state}
            since={waitSince}
            settling={settling}
            onSettled={settled}
          />
        ) : (
          <Deck
            key={`${session}:${epoch}`}
            controller={controller}
            review={review}
            state={state!}
            feelings={feelings}
            onFeel={(candidate, feeling) =>
              setFeelings((current) => {
                const next = new Map(current);
                if (feeling) next.set(candidate, feeling);
                else next.delete(candidate);
                return next;
              })
            }
            onPaint={paint}
            onRethink={() => toEntry(true)}
            onMore={() => void more()}
          />
        )}
      </main>

      {errorText && error?.message !== "EXPIRED" ? (
        <div className="toast-region">
          <div className="toast" role="alert">
            <Icon name="alert" size={17} className="toast__icon" />
            <p className="toast__text">{errorText}</p>
            {state ? (
              <button
                type="button"
                className="toast__action"
                disabled={controller.busy}
                onClick={() => void controller.refresh()}
              >
                刷新状态
              </button>
            ) : null}
            {controller.pending ? (
              <button
                type="button"
                className="toast__action toast__action--primary"
                disabled={controller.busy}
                onClick={() => void controller.retry()}
              >
                重试原操作
              </button>
            ) : null}
          </div>
        </div>
      ) : null}
    </>
  );
}
