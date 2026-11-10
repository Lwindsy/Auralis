import { message } from "../../api/messages.ts";
import type { ProductCard } from "../../api/types.ts";
import { useStore } from "../../state/hooks.ts";
import {
  ACTIVE_REVIEW,
  FAILURE_LABELS,
  type LongReviewController,
} from "../../state/review.ts";
import { Inline, ReviewText } from "./ReviewText.tsx";

const SECTION_ORDER = [
  "production",
  "instrumentation",
  "vocals",
  "mood",
] as const;

/** Five plucked strings: the tiny "still writing" signal for one review. */
function Writing() {
  return (
    <svg
      className="writing"
      viewBox="0 0 60 24"
      aria-hidden="true"
      focusable="false"
    >
      {[0, 1, 2, 3, 4].map((i) => (
        <path
          key={i}
          d={`M${6 + i * 12} 4 Q ${6 + i * 12} 12 ${6 + i * 12} 20`}
        />
      ))}
    </svg>
  );
}

/**
 * Short review and long review share one place. Asking for the long one keeps the short
 * text while it is being written (with a small string animation); once ready, the short
 * review folds away as the long one unfolds. Failures say so and offer an explicit retry.
 */
export function Reviews({
  card,
  review,
  available,
}: {
  card: ProductCard;
  review: LongReviewController;
  /** False for an album already handed back to the server (marked 听过了). */
  available: boolean;
}) {
  useStore(review);
  const open = review.isOpen && review.card?.candidate_id === card.candidate_id;
  const status = open ? (review.view?.status ?? card.long_review_state) : null;
  const sections = open ? review.view?.review?.sections : null;
  const text =
    status === "READY" && sections
      ? SECTION_ORDER.map((key) => sections[key])
          .filter((part) => part && part.trim())
          .join("\n\n")
      : null;
  const expanded = !!text;
  const waiting =
    open &&
    !expanded &&
    (status === "READY" || ACTIVE_REVIEW.includes(status!));
  const failed = open && !expanded && !waiting;

  return (
    <div className="reviews" data-expanded={expanded || undefined}>
      <div className="reviews__short">
        <div>
          <p className="reviews__lead">
            <Inline text={card.short_review.text} />
          </p>
        </div>
      </div>
      <div className="reviews__long" aria-live="polite">
        <div>
          {text ? <ReviewText text={text} className="reviews__prose" /> : null}
        </div>
      </div>
      <div className="reviews__bar">
        {available ? (
          <button
            type="button"
            className="reviews__toggle"
            aria-expanded={expanded}
            data-waiting={waiting || undefined}
            onClick={() => (open ? review.close() : void review.open(card))}
          >
            {waiting ? <Writing /> : null}
            <span>
              {expanded ? "收起长评" : waiting ? "长评写作中" : "读长评"}
            </span>
          </button>
        ) : null}
        {failed && status ? (
          <span className="reviews__failed" role="status">
            {FAILURE_LABELS[status] ?? "长评暂不可用"}
            {review.view?.message_key
              ? `：${message(review.view.message_key, "")}`
              : ""}
            <button
              type="button"
              className="text-link"
              disabled={review.busy}
              onClick={() => void review.write("regenerate")}
            >
              重新生成
            </button>
          </span>
        ) : null}
        {open && review.error ? (
          <span className="reviews__failed" role="alert">
            {message(review.error.messageKey ?? review.error.code)}
            <button
              type="button"
              className="text-link"
              disabled={review.busy}
              onClick={() =>
                review.error?.retry && review.pending
                  ? void review.write(review.pending.operation)
                  : void review.read()
              }
            >
              再试一次
            </button>
          </span>
        ) : null}
      </div>
    </div>
  );
}
