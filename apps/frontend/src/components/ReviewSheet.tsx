import { message } from "../api/messages.ts";
import { useStore } from "../state/hooks.ts";
import {
  ACTIVE_REVIEW,
  FAILURE_LABELS,
  type LongReviewController,
} from "../state/review.ts";
import { albumSeed } from "../lib/cover.ts";
import { Button } from "./Button.tsx";
import { Cover } from "./Cover.tsx";
import { Icon } from "./Icon.tsx";
import { Inline, ReviewText } from "./results/ReviewText.tsx";
import { Sheet } from "./Sheet.tsx";

const SECTION_ORDER = [
  "production",
  "instrumentation",
  "vocals",
  "mood",
] as const;

export function ReviewSheet({ review }: { review: LongReviewController }) {
  useStore(review);
  const card = review.card;
  const view = review.view;
  return (
    <Sheet
      open={review.isOpen}
      onClose={() => review.close()}
      labelledBy="review-title"
      className="review-sheet"
    >
      {card ? (
        <>
          <header className="sheet__head">
            <Cover
              seed={albumSeed(card.identity.title, card.identity.artist_credit)}
              title={card.identity.title}
              url={card.cover_url}
              size="thumb"
            />
            <div className="sheet__titles">
              <p className="eyebrow">详细乐评</p>
              <h2 id="review-title" className="sheet__title display">
                {card.identity.title}
              </h2>
              <p className="sheet__byline">
                {card.identity.artist_credit}
                {card.identity.first_release_year
                  ? ` · ${card.identity.first_release_year}`
                  : ""}
              </p>
            </div>
            <Button
              variant="ghost"
              iconOnly
              icon="close"
              className="sheet__close"
              onClick={() => review.close()}
            >
              关闭乐评
            </Button>
          </header>
          <div className="sheet__body">
            <blockquote className="review-lead">
              <Inline text={card.short_review.text} />
            </blockquote>
            <ReviewBody
              review={review}
              status={view?.status ?? card.long_review_state}
            />
            {review.error ? (
              <div className="review-error" role="alert">
                <p>{message(review.error.messageKey ?? review.error.code)}</p>
                <Button
                  variant="secondary"
                  size="sm"
                  icon="refresh"
                  locked={review.busy}
                  onClick={() =>
                    review.error?.retry && review.pending
                      ? void review.write(review.pending.operation)
                      : void review.read()
                  }
                >
                  {review.error.retry && review.pending
                    ? "重试原操作"
                    : "刷新乐评状态"}
                </Button>
              </div>
            ) : null}
          </div>
        </>
      ) : null}
    </Sheet>
  );
}

function ReviewBody({
  review,
  status,
}: {
  review: LongReviewController;
  status: NonNullable<LongReviewController["view"]>["status"];
}) {
  const sections = review.view?.review?.sections;
  if (status === "READY" && sections) {
    const text = SECTION_ORDER.map((key) => sections[key])
      .filter((part) => part && part.trim())
      .join("\n\n");
    return (
      <article className="review-body">
        <ReviewText text={text} className="review-prose" />
      </article>
    );
  }
  if (ACTIVE_REVIEW.includes(status) || status === "READY") {
    return (
      <div className="review-pending" role="status">
        <p className="review-pending__title">
          <span className="dots" aria-hidden="true">
            <span />
            <span />
            <span />
          </span>
          详细乐评生成中
        </p>
        <p className="review-pending__hint">
          完成后会自动出现，已优先生成这一篇。
        </p>
        <div className="review-pending__lines" aria-hidden="true">
          {[96, 88, 92, 64, 0, 94, 90, 72].map((width, index) =>
            width ? (
              <span
                key={index}
                className="skeleton-line placeholder"
                style={{ width: `${width}%` }}
              />
            ) : (
              <span key={index} className="review-pending__gap" />
            ),
          )}
        </div>
      </div>
    );
  }
  return (
    <div className="review-failed" role="status">
      <Icon name="alert" size={22} />
      <p className="review-failed__title">
        {FAILURE_LABELS[status] ?? "乐评暂不可用"}
      </p>
      <p className="review-failed__hint">
        {review.view?.message_key
          ? message(review.view.message_key, "这篇乐评没有完成。")
          : "这篇乐评没有完成。"}
        需要时可以重新生成，不会自动重试。
      </p>
      <Button
        variant="primary"
        size="sm"
        icon="refresh"
        locked={review.busy}
        onClick={() => void review.write("regenerate")}
      >
        重新生成
      </Button>
    </div>
  );
}
