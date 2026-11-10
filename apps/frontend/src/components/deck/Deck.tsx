import {
  useEffect,
  useRef,
  useState,
  type CSSProperties,
  type ReactNode,
} from "react";
import { message } from "../../api/messages.ts";
import type { ProductState } from "../../api/types.ts";
import { albumSeed, coverArt } from "../../lib/cover.ts";
import type { DiscoveryController } from "../../state/controller.ts";
import {
  freshDeck,
  reconcileDeck,
  sameDeck,
  type DeckEntry,
} from "../../state/deck.ts";
import { useStore } from "../../state/hooks.ts";
import { supplying } from "../../state/input.ts";
import type { LongReviewController } from "../../state/review.ts";
import { Cover } from "../Cover.tsx";
import { pluck } from "../motion/Strings.tsx";
import { Frame } from "./Frame.tsx";
import { Reviews } from "./LongReview.tsx";
import { Reaction } from "./Reaction.tsx";

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

const NIGHT = { base: "#121212", ink: "#efece6" };

function emptyCopy(state: ProductState) {
  if (state.supply_state === "FINAL_EXHAUSTED") return "这次的候选都听完了";
  if (state.status === "NO_MATCH") return "这次没找到合适的专辑";
  if (state.status === "FAILED" || state.status === "INTERRUPTED")
    return message(state.message_key, "这次推荐没能完成");
  return "这一组听完了";
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
  const [entries, setEntries] = useState<DeckEntry[]>(() => freshDeck(state));
  const [index, setIndex] = useState(0);
  const [dir, setDir] = useState<"next" | "prev">("next");
  const [ghost, setGhost] = useState<{ entry: DeckEntry; dir: string } | null>(
    null,
  );
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

  const art = current
    ? coverArt(
        albumSeed(
          current.card.identity.title,
          current.card.identity.artist_credit,
        ),
      )
    : null;
  useEffect(() => {
    onPaint(art?.base ?? NIGHT.base, art?.ink ?? NIGHT.ink);
  }, [art?.base, art?.ink, onPaint]);

  const go = (target: number) => {
    const bounded = Math.max(0, Math.min(last, target));
    if (bounded === position) return;
    const outgoing = entries[position];
    const direction = bounded > position ? "next" : "prev";
    review.close();
    clearTimeout(ghostTimer.current);
    setGhost(outgoing ? { entry: outgoing, dir: direction } : null);
    ghostTimer.current = setTimeout(() => setGhost(null), 560);
    setDir(direction);
    setIndex(bounded);
    pluck(0.9);
  };

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

  const heard = async (entry: DeckEntry) => {
    if (entry.heard) {
      const undo = controller.undoable;
      if (undo?.candidateId !== entry.candidateId) return;
      if (await controller.undo())
        setEntries((list) =>
          list.map((item) =>
            item.candidateId === entry.candidateId
              ? { ...item, heard: false }
              : item,
          ),
        );
      return;
    }
    const mark = (value: boolean) =>
      setEntries((list) =>
        list.map((item) =>
          item.candidateId === entry.candidateId
            ? { ...item, heard: value }
            : item,
        ),
      );
    mark(true);
    pluck(0.6);
    const done = await controller.action("card", entry.slotId, "HEARD");
    if (!done && !controller.pending) mark(false);
  };

  const spread = (entry: DeckEntry, leaving?: string): ReactNode => {
    const { title, artist_credit, first_release_year, primary_genres } =
      entry.card.identity;
    const feeling = feelings.get(entry.candidateId) ?? null;
    const available = !entry.heard;
    const undoable =
      entry.heard && controller.undoable?.candidateId === entry.candidateId;
    return (
      <article
        key={`${entry.candidateId}${leaving ? ":ghost" : ""}`}
        className="spread"
        aria-label={title}
        aria-hidden={leaving ? true : undefined}
        data-leaving={leaving}
        data-dir={leaving ? undefined : dir}
        inert={leaving ? true : undefined}
      >
        <div className="panel panel--sleeve">
          <Frame />
          <div className="sleeve-art">
            <Cover
              seed={albumSeed(title, artist_credit)}
              title={title}
              artist={artist_credit}
              url={entry.card.cover_url}
            />
          </div>
          <div className="panel__caption">
            <h2 className="album-title">{title}</h2>
            <p className="album-artist">
              {artist_credit}
              {first_release_year ? (
                <span className="mono">{first_release_year}</span>
              ) : null}
            </p>
          </div>
        </div>
        <div className="panel panel--notes">
          <Frame />
          {primary_genres.length ? (
            <ul
              className="tags"
              role="list"
              aria-label="目录风格标签"
              title="来自音乐目录的风格标签，只是线索；与需求的关系以短评为准。"
            >
              {primary_genres.slice(0, 4).map((genre, i) => (
                <li key={genre.genre_id} style={{ "--i": i } as CSSProperties}>
                  {genre.name}
                </li>
              ))}
            </ul>
          ) : null}
          {state.analysis_mode === "BASIC" ? (
            <p className="basic-note">基础推荐 · 本次个性化未完成</p>
          ) : null}
          <Reviews card={entry.card} review={review} available={available} />
          <div className="reacts" role="group" aria-label="你的感受">
            <Reaction
              kind="like"
              label="我喜欢！"
              done={feeling === "like"}
              onToggle={() => {
                if (feeling !== "like") pluck(1.2);
                onFeel(entry.candidateId, feeling === "like" ? null : "like");
              }}
            />
            <Reaction
              kind="meh"
              label="一般般"
              done={feeling === "meh"}
              onToggle={() => {
                if (feeling !== "meh") pluck(0.25);
                onFeel(entry.candidateId, feeling === "meh" ? null : "meh");
              }}
            />
            <Reaction
              kind="heard"
              label="听过了"
              done={entry.heard}
              disabled={controller.locked || (entry.heard && !undoable)}
              title={
                entry.heard
                  ? undoable
                    ? "再点一次撤回"
                    : "已记为听过"
                  : "记为听过，之后不再推荐"
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
        <svg
          className="writing writing--large"
          viewBox="0 0 60 24"
          aria-hidden="true"
        >
          {[0, 1, 2, 3, 4].map((i) => (
            <path
              key={i}
              d={`M${6 + i * 12} 4 Q ${6 + i * 12} 12 ${6 + i * 12} 20`}
            />
          ))}
        </svg>
        <p>正在再找一张</p>
      </div>
    ) : (
      <div className="tail finale" role="group" aria-label="接下来">
        <p className="finale__lead">
          {entries.length ? "这一组听完了" : emptyCopy(state)}
        </p>
        <div className="finale__choices">
          <button type="button" className="choice" onClick={onRethink}>
            <span className="choice__eyebrow">不满意？</span>
            <span className="choice__title">换一些想法吧</span>
          </button>
          <button
            type="button"
            className="choice choice--more"
            disabled={controller.locked}
            onClick={onMore}
          >
            <span className="choice__eyebrow">同样的需求</span>
            <span className="choice__title">再给我来一些</span>
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
            按原需求重新复筛
          </button>
        ) : null}
      </div>
    );

  return (
    <section className="deck" aria-label="推荐">
      <button
        type="button"
        className="edge edge--prev"
        aria-label="上一张"
        disabled={position === 0}
        onClick={() => go(position - 1)}
      >
        <svg viewBox="0 0 24 64" aria-hidden="true">
          <path d="M18 8 6 32l12 24" />
        </svg>
        <span>上一张</span>
      </button>
      <div className="deck__stage" data-dir={dir}>
        {ghost ? spread(ghost.entry, ghost.dir) : null}
        {current ? spread(current) : tail}
      </div>
      <button
        type="button"
        className="edge edge--next"
        aria-label="下一张"
        disabled={position >= last}
        onClick={() => go(position + 1)}
      >
        <svg viewBox="0 0 24 64" aria-hidden="true">
          <path d="M6 8l12 24L6 56" />
        </svg>
        <span>下一张</span>
      </button>
      <ol className="pips" aria-label="位置">
        {entries.map((entry, i) => (
          <li
            key={entry.candidateId}
            data-current={i === position || undefined}
            data-heard={entry.heard || undefined}
            style={{ "--i": i } as CSSProperties}
          >
            <button
              type="button"
              aria-label={`第 ${i + 1} 张`}
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
          <button type="button" aria-label="接下来" onClick={() => go(last)} />
        </li>
      </ol>
    </section>
  );
}
