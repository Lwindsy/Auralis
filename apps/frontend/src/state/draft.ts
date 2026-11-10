import type { DiscoveryInput } from "../api/types.ts";

/** Unsubmitted input on the entry page, including the raw listener-threshold field. */
export interface EntryDraft {
  input: DiscoveryInput;
  audience: string;
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
  audience:
    input.audience_min_listeners === undefined
      ? ""
      : String(input.audience_min_listeners),
});
