import type { DiscoveryInput } from "../api/types.ts";

export const REACH_DEFAULT = 10;

/** Inclusive ListenBrainz sample bounds for an integer slider percentage. */
export function reachToListeners(reach: number): { min: number; max?: number } {
  if (reach <= 40) {
    const offset = Math.ceil(reach * 2.5);
    return { min: offset, max: 100 + offset };
  }
  if (reach <= 70) return { min: 100 + reach * 3, max: 150 + reach * 3 };
  return { min: 300 + reach * 2 };
}

/** Restore the slider from its lower bound, including older lower-only inputs. */
export function listenersToReach(listeners: number) {
  let nearest = 0;
  for (let reach = 1; reach <= 100; reach++) {
    if (
      Math.abs(reachToListeners(reach).min - listeners) <
      Math.abs(reachToListeners(nearest).min - listeners)
    ) {
      nearest = reach;
    }
  }
  return nearest;
}

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
