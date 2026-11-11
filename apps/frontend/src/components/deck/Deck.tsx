import {
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type CSSProperties,
  type ReactNode,
} from "react";
import { ApiError } from "../../api/client.ts";
import { message } from "../../api/messages.ts";
import type { ProductState } from "../../api/types.ts";
import type { Room } from "../../lib/color.ts";
import { copy, useCopy } from "../../i18n/index.ts";
import type { DiscoveryController } from "../../state/controller.ts";
import {
  freshDeck,
  reconcileDeck,
  sameDeck,
  type DeckEntry,
} from "../../state/deck.ts";
import { useStore } from "../../state/hooks.ts";
import { supplying } from "../../state/input.ts";
import { labels } from "../../state/labels.ts";
import type { LongReviewController } from "../../state/review.ts";
import { ripple } from "../motion/Contours.tsx";
import { Reviews } from "./LongReview.tsx";
import { Reaction } from "./Reaction.tsx";
import { GenreWave } from "./GenreWave.tsx";
import { MiniWave } from "./MiniWave.tsx";
import { SlicedCover, type CoverExit } from "./SlicedCover.tsx";
import { FLIGHT_MS, GroupStack, STACK_MAX } from "./GroupStack.tsx";
import { reducedMotion } from "../../lib/motion.ts";
import { useRoom } from "./useRoom.ts";
import { LINER_CLOSE_MS } from "./liner.ts";
import { prefetchCover } from "../../state/covers.ts";

export type Feeling = "like" | "meh";

export interface DeckProps {
  controller: DiscoveryController;
  review: LongReviewController;
  state: ProductState;
  feelings: ReadonlyMap<string, Feeling>;
  onFeel: (candidateId: string, feeling: Feeling | null) => void;
  onPaint: (room: string, ink: string) => void;
  onRethink: () => void;
  onMore: () => void;
}

const NIGHT: Room = { room: "#0d1220", ink: "#efe9df", spark: "#e0714f" };
/** After a reaction, all three rest this long before taking another press. */
const REST_MS = 1100;
/** Refusals that only mean "not now" (a supply or another write is still running). */
const TRANSIENT = new Set([
  "EXECUTOR_BUSY",
  "REVISION_CONFLICT",
  "IDEMPOTENCY_CONFLICT",
  "UNEXPECTED_STATUS",
]);
const sleep = (ms: number) => new Promise((done) => setTimeout(done, ms));

function emptyCopy(state: ProductState) {
  const words = copy().deck;
  if (state.supply_state === "FINAL_EXHAUSTED") return words.exhausted;
  if (state.status === "NO_MATCH") return words.noMatch;
  if (state.status === "FAILED" || state.status === "INTERRUPTED")
    return message(state.message_key, words.failed);
  return words.done;
}

/**
 * The listening room. One album at a time: sleeve, title and artist on the left; catalogue
 * tags, the short review (which gives way to the long one) and three reactions on the right.
 * The edges of the screen are the previous / next controls. After the last album the room
 * offers to rethink the request or to bring more.
 */
export function Deck({
  controller,
  review,
  state,
  feelings,
  onFeel,
  onPaint,
  onRethink,
  onMore,
}: DeckProps) {
  useStore(controller);
  const words = useCopy().deck;
  const [entries, setEntries] = useState<DeckEntry[]>(() => freshDeck(state));
  const [index, setIndex] = useState(0);
  const [dir, setDir] = useState<"next" | "prev">("next");
  const [ghost, setGhost] = useState<{
    entry: DeckEntry;
    dir: string;
    room: Room;
    exit: CoverExit;
  } | null>(null);
  const stage = useRef<HTMLDivElement>(null);
  const ghostTimer = useRef<ReturnType<typeof setTimeout> | undefined>(
    undefined,
  );
  useEffect(() => () => clearTimeout(ghostTimer.current), []);

  // Follow each new server state during render (no effect round-trip).
  const [seen, setSeen] = useState(state);
  if (seen !== state) {
    setSeen(state);
    const next = reconcileDeck(entries, state);
    if (!sameDeck(entries, next)) setEntries(next);
  }

  const busy = supplying(state) || controller.continuing;
  const last = entries.length; // the tail position after the final album
  const position = Math.min(index, last);
  const current = entries[position] ?? null;
  const nextCover = entries[position + 1]?.card.cover_url;
  useEffect(() => {
    prefetchCover(nextCover);
  }, [nextCover]);

  const room = useRoom(current?.card ?? null) ?? NIGHT;
  useEffect(() => {
    onPaint(room.room, room.ink);
  }, [room.room, room.ink, onPaint]);

  // Catalogue genre names seen on cards help the start page name picks after a reload.
  useEffect(() => {
    labels.rememberGenres(
      entries.flatMap((entry) => entry.card.identity.primary_genres),
    );
  }, [entries]);

  // A long review stays open for albums still in the room, 听过了 ones included.
  useEffect(() => {
    review.reconcile(state, new Set(entries.map((entry) => entry.candidateId)));
  });

  // Reactions rest briefly after each press.
  const [settling, setSettling] = useState<{
    candidateId: string;
    kind: string;
  } | null>(null);
  const restTimer = useRef<ReturnType<typeof setTimeout> | undefined>(
    undefined,
  );
  useEffect(() => () => clearTimeout(restTimer.current), []);
  const rest = (candidateId: string, kind: string) => {
    setSettling({ candidateId, kind });
    clearTimeout(restTimer.current);
    restTimer.current = setTimeout(() => setSettling(null), REST_MS);
  };

  // Moving on while the liner notes are out puts the sheet back first, then turns the
  // record, so the album never jumps from the header straight into its exit. Presses made
  // meanwhile add up to one move.
  const navTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const pendingTurn = useRef<number | null>(null);
  useEffect(() => () => clearTimeout(navTimer.current), []);
  const go = (target: number) => {
    const bound = (value: number) => Math.max(0, Math.min(last, value));
    if (pendingTurn.current !== null) {
      pendingTurn.current = bound(pendingTurn.current + target - position);
      return;
    }
    const reading = stage.current?.querySelector(
      ".cut[data-reading]:not([data-leaving])",
    );
    if (reading && bound(target) !== position) {
      review.close();
      pendingTurn.current = bound(target);
      navTimer.current = setTimeout(() => {
        const next = pendingTurn.current;
        pendingTurn.current = null;
        if (next !== null) turn(next);
      }, LINER_CLOSE_MS - 140);
      return;
    }
    turn(target);
  };

  const turn = (target: number) => {
    const bounded = Math.max(0, Math.min(last, target));
    if (bounded === position) return;
    const outgoing = entries[position];
    const direction = bounded > position ? "next" : "prev";
    // Into the finale the sleeve does not break into strips over the closing words: it
    // flies whole into the group's stack, or just fades when there is no stack to join.
    const stacked =
      !!outgoing &&
      entries
        .slice(-STACK_MAX)
        .some((e) => e.candidateId === outgoing.candidateId);
    const exit: CoverExit =
      bounded !== last
        ? "slices"
        : stacked && !busy && !reducedMotion()
          ? "flight"
          : "quiet";
    review.close();
    clearTimeout(ghostTimer.current);
    // The leaving copy keeps the colours of the room it is leaving.
    setGhost(outgoing ? { entry: outgoing, dir: direction, room, exit } : null);
    ghostTimer.current = setTimeout(
      () => setGhost(null),
      exit === "flight" ? FLIGHT_MS + 40 : 760,
    );
    setDir(direction);
    setIndex(bounded);
    ripple(1, window.innerWidth * 0.3, window.innerHeight * 0.45);
  };

  // The flight into the stack: from the leaving sleeve to its own (unrotated) place in the
  // stack, landing with that card's tilt. One element, transform only.
  useLayoutEffect(() => {
    if (ghost?.exit !== "flight" || !stage.current) return;
    const sleeve = stage.current.querySelector<HTMLElement>(
      ".cut[data-leaving] .sliced",
    );
    const place = stage.current.querySelector<HTMLElement>(
      `.stack__slot[data-candidate="${CSS.escape(ghost.entry.candidateId)}"]`,
    );
    if (!sleeve || !place || !sleeve.animate) return;
    const from = sleeve.getBoundingClientRect();
    const to = place.getBoundingClientRect();
    if (!from.width || !to.width) return;
    const dx = to.left - from.left;
    const dy = to.top - from.top;
    const scale = to.width / from.width;
    const tilt =
      parseFloat(getComputedStyle(place).getPropertyValue("--r")) || 0;
    // The sleeve turns about its own centre while it scales from its corner (the
    // transform origin is the top left), so every step names the centre explicitly.
    const step = (x: number, y: number, size: number, turn: number) => {
      const half = (from.width * size) / 2;
      return `translate(${x + half}px, ${y + half}px) rotate(${turn}deg) translate(${-half}px, ${-half}px) scale(${size})`;
    };
    const flight = sleeve.animate(
      [
        { transform: step(0, 0, 1, 0) },
        {
          transform: step(
            dx * 0.42,
            dy * 0.42 - 36,
            (1 + scale) / 2,
            tilt * 0.4,
          ),
          offset: 0.45,
        },
        { transform: step(dx, dy, scale, tilt) },
      ],
      {
        duration: FLIGHT_MS,
        easing: "cubic-bezier(0.5, 0, 0.2, 1)",
        fill: "forwards",
      },
    );
    return () => flight.cancel();
  }, [ghost]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement;
      if (target.closest("input, textarea")) return;
      if (event.key === "ArrowRight") go(position + 1);
      else if (event.key === "ArrowLeft") go(position - 1);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  const mark = (candidateId: string, value: boolean) =>
    setEntries((list) =>
      list.map((item) =>
        item.candidateId === candidateId ? { ...item, heard: value } : item,
      ),
    );

  // 听过了 is shown at once and written in order. Writes wait while the server is still
  // filling a slot (it refuses card actions then) and quietly try again on refusals that
  // only mean "not yet"; only a real failure takes the mark back. A lost response is left
  // to the person (retry with the original key, or re-read the state) as before.
  const queue = useRef<string[]>([]);
  const flushing = useRef(false);
  const [queued, setQueued] = useState<ReadonlySet<string>>(() => new Set());
  const syncQueued = () => setQueued(new Set(queue.current));
  const changed = (ms: number) =>
    new Promise<void>((done) => {
      const timer = setTimeout(() => {
        stop();
        done();
      }, ms);
      const stop = controller.subscribe(() => {
        clearTimeout(timer);
        stop();
        done();
      });
    });
  const flush = async () => {
    if (flushing.current) return;
    flushing.current = true;
    let attempts = 0;
    try {
      while (queue.current.length) {
        const live = controller.state;
        if (!live) break;
        if (controller.busy || supplying(live)) {
          await changed(1500);
          continue;
        }
        if (controller.pending) {
          // A lost response is the person's call (retry it, or re-read the state);
          // nothing new is written until it is settled.
          await changed(1500);
          continue;
        }
        const candidateId = queue.current[0]!;
        const slot = live.slots.find(
          (item) =>
            item.status === "READY" && item.card?.candidate_id === candidateId,
        );
        if (!slot) {
          // No longer in a slot: there is nothing left to tell the server.
          queue.current.shift();
          syncQueued();
          continue;
        }
        const done = await controller.action("card", slot.slot_id, "HEARD");
        if (done) {
          queue.current.shift();
          syncQueued();
          attempts = 0;
          continue;
        }
        if (controller.pending) continue;
        const error = controller.error;
        const code = error instanceof ApiError ? error.message : "";
        if (TRANSIENT.has(code) && attempts < 8) {
          attempts++;
          controller.clearError();
          await sleep(500 * attempts);
          await controller.refresh();
          continue;
        }
        queue.current.shift();
        syncQueued();
        mark(candidateId, false);
        attempts = 0;
      }
    } finally {
      flushing.current = false;
    }
  };

  const heard = async (entry: DeckEntry) => {
    rest(entry.candidateId, "heard");
    if (entry.heard) {
      // Still waiting to be written: just take it back here.
      if (queue.current.includes(entry.candidateId)) {
        queue.current = queue.current.filter((id) => id !== entry.candidateId);
        syncQueued();
        mark(entry.candidateId, false);
        return;
      }
      const undo = controller.undoable;
      if (undo?.candidateId !== entry.candidateId) return;
      while (controller.locked || flushing.current) await changed(1500);
      if (await controller.undo()) mark(entry.candidateId, false);
      return;
    }
    mark(entry.candidateId, true);
    ripple(0.6, window.innerWidth * 0.7, window.innerHeight * 0.7);
    queue.current = [...queue.current, entry.candidateId];
    syncQueued();
    void flush();
  };

  const spread = (
    entry: DeckEntry,
    leaving?: string,
    colours?: Room,
    exit?: CoverExit,
  ): ReactNode => {
    const { title, artist_credit, first_release_year } = entry.card.identity;
    const feeling = feelings.get(entry.candidateId) ?? null;
    const waiting = queued.has(entry.candidateId);
    const undoable =
      entry.heard &&
      (waiting || controller.undoable?.candidateId === entry.candidateId);
    const resting = settling?.candidateId === entry.candidateId;
    const settles = (kind: string) => resting && settling?.kind === kind;
    return (
      <article
        key={`${entry.candidateId}${leaving ? ":ghost" : ""}`}
        className="cut"
        aria-label={title}
        aria-hidden={leaving ? true : undefined}
        data-leaving={leaving}
        data-exit={leaving ? exit : undefined}
        data-dir={leaving ? undefined : dir}
        data-heard={entry.heard || undefined}
        inert={leaving ? true : undefined}
        style={
          (colours
            ? {
                "--page-room": colours.room,
                "--page-ink": colours.ink,
                "--spark": colours.spark,
              }
            : { "--spark": room.spark }) as unknown as CSSProperties
        }
      >
        <div className="cut__sleeve">
          <SlicedCover
            title={title}
            artist={artist_credit}
            url={entry.card.cover_url}
            leaving={leaving ? (exit ?? "slices") : false}
          />
          <div className="cut__caption">
            <h2 className="album-title">{title}</h2>
            <p className="album-artist">
              {artist_credit}
              {first_release_year ? (
                <span className="mono">{first_release_year}</span>
              ) : null}
            </p>
          </div>
        </div>
        <div className="cut__words">
          <GenreWave card={entry.card} still={!!leaving} />
          {state.analysis_mode === "BASIC" ? (
            <p className="basic-note">{words.basic}</p>
          ) : null}
          <Reviews
            card={entry.card}
            review={review}
            delay={1250}
            still={!!leaving}
          />
          <div className="reacts" role="group" aria-label={words.feelings}>
            <Reaction
              kind="like"
              label={words.like}
              done={feeling === "like"}
              resting={resting}
              settling={settles("like")}
              onToggle={() => {
                rest(entry.candidateId, "like");
                if (feeling !== "like")
                  ripple(
                    1.3,
                    window.innerWidth * 0.7,
                    window.innerHeight * 0.7,
                  );
                onFeel(entry.candidateId, feeling === "like" ? null : "like");
              }}
            />
            <Reaction
              kind="meh"
              label={words.meh}
              done={feeling === "meh"}
              resting={resting}
              settling={settles("meh")}
              onToggle={() => {
                rest(entry.candidateId, "meh");
                if (feeling !== "meh")
                  ripple(
                    0.3,
                    window.innerWidth * 0.7,
                    window.innerHeight * 0.7,
                  );
                onFeel(entry.candidateId, feeling === "meh" ? null : "meh");
              }}
            />
            <Reaction
              kind="heard"
              label={words.heard}
              done={entry.heard}
              resting={resting}
              settling={settles("heard")}
              disabled={entry.heard && !undoable}
              title={
                entry.heard
                  ? undoable
                    ? words.undoHeard
                    : words.heardKept
                  : words.markHeard
              }
              onToggle={() => void heard(entry)}
            />
          </div>
        </div>
      </article>
    );
  };

  const basic = state.analysis_mode === "BASIC";
  const tail =
    busy && position === last ? (
      <div className="tail tail--supply" role="status">
        <MiniWave bars={11} live className="tail__wave" />
        <p>{words.supplying}</p>
      </div>
    ) : (
      <div className="tail finale" role="group" aria-label={words.next}>
        <GroupStack
          entries={entries}
          landing={ghost?.exit === "flight" ? ghost.entry.candidateId : null}
        />
        <p className="finale__lead">
          {entries.length ? words.done : emptyCopy(state)}
        </p>
        <div className="finale__choices">
          <button type="button" className="choice" onClick={onRethink}>
            <MiniWave bars={13} calm className="choice__wave" />
            <span className="choice__eyebrow">{words.unhappy}</span>
            <span className="choice__title">{words.rethink}</span>
          </button>
          <button
            type="button"
            className="choice choice--more"
            disabled={controller.locked}
            onClick={onMore}
          >
            <MiniWave bars={13} live className="choice__wave" />
            <span className="choice__eyebrow">{words.same}</span>
            <span className="choice__title">{words.more}</span>
          </button>
        </div>
        {basic && entries.length ? (
          <button
            type="button"
            className="text-link finale__rescreen"
            disabled={controller.locked}
            onClick={() => {
              setIndex(0);
              void controller.action("rescreen");
            }}
          >
            {words.rescreen}
          </button>
        ) : null}
      </div>
    );

  return (
    <section className="deck" aria-label={words.label}>
      <button
        type="button"
        className="edge edge--prev"
        aria-label={words.prev}
        disabled={position === 0}
        onClick={() => go(position - 1)}
      >
        <svg viewBox="0 0 24 64" aria-hidden="true">
          <path d="M18 8 6 32l12 24" />
        </svg>
        <span>{words.prev}</span>
      </button>
      <div className="deck__stage" data-dir={dir} ref={stage}>
        {ghost ? spread(ghost.entry, ghost.dir, ghost.room, ghost.exit) : null}
        {current ? spread(current) : tail}
      </div>
      <button
        type="button"
        className="edge edge--next"
        aria-label={words.forward}
        disabled={position >= last}
        onClick={() => go(position + 1)}
      >
        <svg viewBox="0 0 24 64" aria-hidden="true">
          <path d="M6 8l12 24L6 56" />
        </svg>
        <span>{words.forward}</span>
      </button>
      <ol className="pips" aria-label={words.position}>
        {entries.map((entry, i) => (
          <li
            key={entry.candidateId}
            data-current={i === position || undefined}
            data-heard={entry.heard || undefined}
            style={{ "--i": i } as CSSProperties}
          >
            <button
              type="button"
              aria-label={words.nth(i + 1)}
              aria-current={i === position || undefined}
              onClick={() => go(i)}
            />
          </li>
        ))}
        <li
          data-current={position === last || undefined}
          data-tail
          style={{ "--i": entries.length } as CSSProperties}
        >
          <button
            type="button"
            aria-label={words.next}
            onClick={() => go(last)}
          />
        </li>
      </ol>
    </section>
  );
}
