import { useLayoutEffect, useRef, useState } from "react";
import { message } from "../../api/messages.ts";
import type { ProductCard } from "../../api/types.ts";
import { useStore } from "../../state/hooks.ts";
import { useCopy } from "../../i18n/index.ts";
import {
  ACTIVE_REVIEW,
  type LongReviewController,
} from "../../state/review.ts";
import { QuietScroll } from "../QuietScroll.tsx";
import { MiniWave } from "./MiniWave.tsx";
import { Playback } from "./Playback.tsx";

const SECTION_ORDER = [
  "production",
  "instrumentation",
  "vocals",
  "mood",
] as const;
/** Long reviews play quicker than the short one; a short pause separates tracks. */
const LONG_PACE = 9;
const TRACK_GAP = 220;

/**
 * Short and long review share one place and one idea: each is played like a track.
 * Asking for the long review keeps the short one while it is being written (a small
 * waveform says so); once ready, the short track lifts away and the long review settles
 * into its place as numbered tracks that play one after another.
 *
 * The two panes are stacked in one stage. Only the stage's height animates (the panes
 * themselves are positioned out of flow, so their text never re-flows during the
 * change) and the panes cross over with opacity and a small drift: smooth and cheap. The
 * entry stays available for albums marked 听过了.
 */
export function Reviews({
  card,
  review,
  delay = 0,
}: {
  card: ProductCard;
  review: LongReviewController;
  /** When the short review starts to play, after the cover has assembled. */
  delay?: number;
}) {
  useStore(review);
  const words = useCopy().review;
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

  // Tracks of the long review play in turn: each starts when the one before has ended.
  const [playing, setPlaying] = useState(0);
  const [shown, setShown] = useState(expanded);
  if (shown !== expanded) {
    setShown(expanded);
    setPlaying(0);
  }

  // The stage takes the height of the pane in front; the change is the only animation
  // that touches layout.
  const stage = useRef<HTMLDivElement>(null);
  const shortPane = useRef<HTMLDivElement>(null);
  const longPane = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    const box = stage.current;
    const front = expanded ? longPane.current : shortPane.current;
    if (!box || !front) return;
    const fit = () => {
      box.style.height = `${front.offsetHeight}px`;
    };
    fit();
    const observer = new ResizeObserver(fit);
    observer.observe(front);
    return () => observer.disconnect();
  }, [expanded]);

  return (
    <div className="reviews" data-expanded={expanded || undefined}>
      <div className="reviews__stage" ref={stage}>
        <div
          className="reviews__pane reviews__pane--short"
          ref={shortPane}
          aria-hidden={expanded || undefined}
          inert={expanded || undefined}
        >
          <Playback
            text={card.short_review.text}
            autoplay
            startDelay={delay}
            pace={20}
            label={words.short}
            className="track--lead"
          />
        </div>
        <div
          className="reviews__pane reviews__pane--long"
          ref={longPane}
          aria-live="polite"
          aria-hidden={!expanded || undefined}
        >
          <QuietScroll>
            <div className="reviews__scroll quiet-scroll">
              {paragraphs.map((text, index) => (
                <Playback
                  key={index}
                  text={text}
                  autoplay={expanded && playing >= index}
                  startDelay={index === 0 ? 520 : TRACK_GAP}
                  pace={LONG_PACE}
                  label={String(index + 1).padStart(2, "0")}
                  onEnd={() =>
                    setPlaying((current) => Math.max(current, index + 1))
                  }
                />
              ))}
            </div>
          </QuietScroll>
        </div>
      </div>
      <div className="reviews__bar">
        <button
          type="button"
          className="reviews__toggle"
          aria-expanded={expanded}
          data-waiting={waiting || undefined}
          onClick={() => (open ? review.close() : void review.open(card))}
        >
          {waiting ? <MiniWave bars={7} live /> : null}
          <span>
            {expanded ? words.close : waiting ? words.writing : words.open}
          </span>
        </button>
        {failed && status ? (
          <span className="reviews__failed" role="status">
            {words.failures[status] ?? words.unavailable}
            {review.view?.message_key
              ? words.detail(message(review.view.message_key, ""))
              : ""}
            <button
              type="button"
              className="text-link"
              disabled={review.busy}
              onClick={() => void review.write("regenerate")}
            >
              {words.regenerate}
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
              {words.again}
            </button>
          </span>
        ) : null}
      </div>
    </div>
  );
}
