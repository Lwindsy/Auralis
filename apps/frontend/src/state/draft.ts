import type { DiscoveryInput } from "../api/types.ts";

/** Listeners that 100% 传播度 stands for; the scale is linear from 0. */
export const REACH_FULL = 500;
/** What the server assumes when no threshold is sent (50 listeners), as 传播度. */
export const REACH_DEFAULT = 10;

/** 传播度 (0–100) → the listener threshold sent to the server (which accepts 1 and up). */
export const reachToListeners = (reach: number) =>
  Math.max(1, Math.round((reach / 100) * REACH_FULL));

/** A listener threshold → 传播度, clamped to the slider. */
export const listenersToReach = (listeners: number) =>
  Math.max(0, Math.min(100, Math.round((listeners / REACH_FULL) * 100)));

/** Unsubmitted input on the entry page, with 传播度 kept as the slider's percentage. */
export interface EntryDraft {
  input: DiscoveryInput;
  reach: number;
}

/**
 * Keeps what the person typed or picked while they look at results and come back. Only in
 * this page's memory: natural language never goes to browser storage. Submitting, a reload
 * or a different session drops it.
 */
export class DraftStore {
  private entry: { owner: string; draft: EntryDraft } | null = null;

  get(owner: string): EntryDraft | null {
    return this.entry?.owner === owner
      ? structuredClone(this.entry.draft)
      : null;
  }

  keep(owner: string, draft: EntryDraft) {
    this.entry = { owner, draft: structuredClone(draft) };
  }

  discard() {
    this.entry = null;
  }
}

export const draftFrom = (input: DiscoveryInput): EntryDraft => ({
  input: structuredClone(input),
  reach:
    input.audience_min_listeners === undefined
      ? REACH_DEFAULT
      : listenersToReach(input.audience_min_listeners),
});
