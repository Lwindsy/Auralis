import { useEffect, useState, type CSSProperties } from "react";
import type { Api } from "./api/client.ts";
import { message } from "./api/messages.ts";
import type { DiscoveryInput } from "./api/types.ts";
import { Button } from "./components/Button.tsx";
import { Callout } from "./components/Callout.tsx";
import { Composer } from "./components/composer/Composer.tsx";
import { Icon } from "./components/Icon.tsx";
import { ReviewSheet } from "./components/ReviewSheet.tsx";
import { paintPage } from "./components/results/Crate.tsx";
import { ResultsView } from "./components/results/ResultsView.tsx";
import { Sheet } from "./components/Sheet.tsx";
import { Wall } from "./components/Wall.tsx";
import { ApiError } from "./api/client.ts";
import type { DiscoveryController } from "./state/controller.ts";
import { useStore } from "./state/hooks.ts";
import type { LongReviewController } from "./state/review.ts";

interface AppProps {
  api: Api;
  controller: DiscoveryController;
  review: LongReviewController;
  fixture: boolean;
}

const dateFormat = new Intl.DateTimeFormat("zh-CN", {
  month: "long",
  day: "numeric",
});

export function App({ api, controller, review, fixture }: AppProps) {
  useStore(controller);
  const state = controller.state;
  const [editing, setEditing] = useState(false);
  const [welcomeDismissed, setWelcomeDismissed] = useState(false);
  const restored = !!state?.restored;
  useEffect(() => {
    if (!restored) return;
    const timer = setTimeout(() => setWelcomeDismissed(true), 6000);
    return () => clearTimeout(timer);
  }, [restored]);
  const hasSession = !!state?.session_id;

  useEffect(() => {
    review.reconcile(state);
  });

  useEffect(() => {
    if (state && !hasSession) return paintPage("#111111", "#f2efe8");
  }, [state, hasSession]);

  // Entering or leaving results is a page change; start it from the top.
  useEffect(() => {
    window.scrollTo({ top: 0 });
  }, [hasSession]);

  // An editor for a session that no longer exists would submit against stale state.
  const editorOpen = editing && hasSession;

  const submitFirst = async (input: DiscoveryInput) => {
    await controller.submit(input);
  };
  const submitEdit = async (input: DiscoveryInput) => {
    if (await controller.submit(input)) setEditing(false);
  };
  const retry = async () => {
    const submission = controller.pending?.path === "/v1/discovery-sessions";
    if ((await controller.retry()) && submission) setEditing(false);
  };

  const error = controller.error;
  const errorText = error
    ? message(
        error instanceof ApiError ? error.details?.message_key : null,
        message(error.message),
      )
    : null;

  return (
    <>
      {fixture ? (
        <p className="fixture-banner">
          契约样例模式 · 合成数据，不是真实推荐服务
        </p>
      ) : null}
      <header className="topbar">
        <div className="topbar__inner">
          <a className="brand" href="/" aria-label="Auralis 首页">
            <span className="brand__mark" aria-hidden="true" />
            <span className="brand__name">Auralis</span>
          </a>
          <p className="topbar__meta">
            {hasSession && state
              ? `保留至 ${dateFormat.format(new Date(state.expires_at))}`
              : null}
          </p>
        </div>
      </header>

      <main className="page">
        {state?.restored && !welcomeDismissed ? (
          <div className="welcome rise" role="status">
            <p>欢迎回来，接着上次的发现。</p>
            <button
              type="button"
              className="icon-button"
              aria-label="知道了"
              onClick={() => setWelcomeDismissed(true)}
            >
              <Icon name="close" size={14} />
            </button>
          </div>
        ) : null}

        {!state ? (
          <div className="connecting rise">
            {controller.busy ? (
              <>
                <div className="eq eq--small" aria-hidden="true">
                  {Array.from({ length: 4 }, (_, i) => (
                    <span key={i} style={{ "--b": i } as CSSProperties} />
                  ))}
                </div>
                <p role="status">正在恢复你的发现…</p>
              </>
            ) : (
              <>
                <p>暂时无法连接到 Auralis。</p>
                <Button
                  variant="primary"
                  icon="refresh"
                  onClick={() => void controller.connect()}
                >
                  重新连接
                </Button>
              </>
            )}
          </div>
        ) : !hasSession ? (
          <section className="landing" aria-labelledby="landing-title">
            <Wall />
            <div className="landing__panel">
              <h1 id="landing-title" className="landing__title">
                下一张，
                <br />
                听什么？
              </h1>
              {state.message_key === "session.expired" ? (
                <Callout tone="info" role="status">
                  {message("session.expired")}
                </Callout>
              ) : null}
              <Composer
                key={state.revision}
                api={api}
                initial={state.editable_input}
                variant="create"
                locked={controller.locked}
                onSubmit={submitFirst}
              />
            </div>
          </section>
        ) : (
          <ResultsView
            api={api}
            controller={controller}
            state={state}
            onEdit={() => setEditing(true)}
            onReview={(card) => void review.open(card)}
          />
        )}
      </main>

      {state ? (
        <Sheet
          open={editorOpen}
          onClose={() => {
            if (!controller.locked) setEditing(false);
          }}
          labelledBy="editor-title"
          width="lg"
          initialFocus="textarea"
          className="editor-sheet"
        >
          <header className="sheet__head sheet__head--plain">
            <div className="sheet__titles">
              <h2 id="editor-title" className="sheet__title">
                修改这次需求
              </h2>
            </div>
            <Button
              variant="ghost"
              iconOnly
              icon="close"
              className="sheet__close"
              disabled={controller.locked}
              onClick={() => setEditing(false)}
            >
              关闭编辑
            </Button>
          </header>
          <div className="sheet__body sheet__body--flush">
            <Composer
              api={api}
              initial={state.editable_input}
              variant="edit"
              locked={controller.locked}
              onSubmit={submitEdit}
              onCancel={() => setEditing(false)}
            />
          </div>
        </Sheet>
      ) : null}

      <ReviewSheet review={review} />

      {errorText && error?.message !== "EXPIRED" ? (
        <div className="toast-region">
          <div className="toast" role="alert">
            <Icon name="alert" size={18} className="toast__icon" />
            <p className="toast__text">{errorText}</p>
            <div className="toast__actions">
              {state ? (
                <Button
                  variant="ghost"
                  size="sm"
                  locked={controller.busy}
                  onClick={() => void controller.refresh()}
                >
                  刷新状态
                </Button>
              ) : null}
              {controller.pending ? (
                <Button
                  variant="primary"
                  size="sm"
                  locked={controller.busy}
                  onClick={() => void retry()}
                >
                  重试原操作
                </Button>
              ) : null}
            </div>
          </div>
        </div>
      ) : null}
    </>
  );
}
