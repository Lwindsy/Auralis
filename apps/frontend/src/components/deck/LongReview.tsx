import { message } from "../../api/messages.ts";
import type { ProductCard } from "../../api/types.ts";
import { useStore } from "../../state/hooks.ts";
import {
  ACTIVE_REVIEW,
  FAILURE_LABELS,
  type LongReviewController,
} from "../../state/review.ts";
import { MiniWave } from "./MiniWave.tsx";
import { Playback, charCount } from "./Playback.tsx";

const SECTION_ORDER = [
  "production",
  "instrumentation",
  "vocals",
  "mood",
] as const;
/** Long reviews play quicker than the short one; a gap separates tracks. */
const LONG_PACE = 9;
const TRACK_GAP = 220;

/**
 * Short and long review share one place and one idea: each is played like a track.
 * Asking for the long review keeps the short one while it is being written (a small
 * waveform says so); once ready, the short track folds away and the long review unfolds
 * as numbered tracks that play one after another. Failures retry only on request.
 */
export function Reviews({
  card,
  review,
  available,
  delay = 0,
}: {
  card: ProductCard;
  review: LongReviewController;
  /** False for an album already handed back to the server (marked 听过了). */
  available: boolean;
  /** When the short review starts to play, after the cover has assembled. */
  delay?: number;
}) {
  useStore(review);
  const open = review.isOpen && review.card?.candidate_id === card.candidate_id;
  const status = open ? (review.view?.status ?? card.long_review_state) : null;
  const sections = open ? review.view?.review?.sections : null;
  const paragraphs =
    status === "READY" && sections
      ? SECTION_ORDER.map((key) => sections[key])
          .flatMap((part) => (part ?? "").split(/\n{2,}/))
          .map((part) => part.trim())
          .filter(Boolean)
      : [];
  const expanded = paragraphs.length > 0;
  const waiting =
    open &&
    !expanded &&
    (status === "READY" || (!!status && ACTIVE_REVIEW.includes(status)));
  const failed = open && !expanded && !waiting;
  // Tracks play one after another: each starts when the previous one has finished.
  const starts = paragraphs.reduce<number[]>(
    (list, _text, index) => [
      ...list,
      index === 0
        ? 500
        : list[index - 1]! +
          charCount(paragraphs[index - 1]!) * LONG_PACE +
          TRACK_GAP,
    ],
    [],
  );

  return (
    <div className="reviews" data-expanded={expanded || undefined}>
      <div className="reviews__short">
        <div>
          <Playback
            text={card.short_review.text}
            delay={delay}
            pace={20}
            label="短评"
            className="track--lead"
          />
        </div>
      </div>
      <div className="reviews__long" aria-live="polite">
        <div>
          {paragraphs.map((text, index) => (
            <Playback
              key={index}
              text={text}
              delay={starts[index]}
              pace={LONG_PACE}
              label={String(index + 1).padStart(2, "0")}
            />
          ))}
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
            {waiting ? <MiniWave bars={7} live /> : null}
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
