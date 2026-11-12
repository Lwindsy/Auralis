import type { Copy } from "./zh-CN.ts";

// Every visible string of the page in English: written to read naturally, not as a
// word-for-word rendering of the Chinese copy. Same keys as `zh-CN.ts`.

const en: Copy = {
  meta: {
    title: "Auralis · Find your next album",
    description:
      "Start from what you love and find albums worth hearing front to back.",
  },
  app: {
    fixture: "Contract fixture mode · synthetic data, not the real service",
    home: "Auralis home",
    language: "Language",
    restoring: "Picking up where you left off…",
    offline: "Can't reach Auralis right now.",
    reconnect: "Reconnect",
    refresh: "Refresh",
    retry: "Try that again",
    privacy: {
      label: "Privacy",
      points: [
        "No sign-up",
        "Anonymous cookie only",
        "Kept for 30 days",
        "May be processed by OpenAI",
      ],
      more: "Details",
      full: "No account needed. The cookie holds only an anonymous session credential. Auralis keeps your submitted preferences, text, and recommendation results for 30 days after your last active interaction. OpenAI may process relevant input to generate personalized recommendations and reviews.",
    },
  },
  entry: {
    doors: {
      text: {
        label: "In your own words",
        hint: "A mood, a moment, a sound — anything goes",
      },
      genres: {
        label: "Genres",
        hint: "Pick what you love, flag what to skip",
      },
      albums: { label: "Albums", hint: "Add a few favorites to steer by" },
    },
    spine: { text: "Words", genres: "Genres", albums: "Albums" },
    examples: [
      "Rainy Sunday afternoon, in the mood for jazz piano",
      "Electronic stuff for a late-night drive, nothing too loud",
      "Warm and a little melancholy, like In Rainbows",
    ],
    hint: "Not sure what to play? Tell us how you feel and get a soundtrack that's all yours",
    resume: "Back to your picks",
    textLabel: "What you want to hear this time",
    textPlaceholder: "e.g. Rainy Sunday afternoon, in the mood for jazz piano",
    tooLong: (max: number, length: number) =>
      `Keep it under ${max} characters — you're at ${length}.`,
    examplesLabel: "Try something like",
    pickedGenres: "Picked genres",
    pickedAlbums: "Picked albums",
    selectedCount: (count: number) => `, ${count} picked`,
    quote: (text: string) => `“${text}”`,
    fold: "Close",
    start: "Start discovering",
  },
  modes: {
    group: "How far to roam",
    familiar: {
      label: "Familiar",
      hint: "Stays close to what you already like",
      line: "Sticks to the lane you gave it. The safe bet.",
    },
    explore: {
      label: "Explore",
      hint: "Keeps your core taste, wanders into nearby genres",
      line: "Same core vibe, with a detour into neighboring genres.",
    },
    surprise: {
      label: "Surprise",
      hint: "Lets the connections reach further",
      line: "Goes further out. Expect the unexpected.",
    },
  },
  reach: {
    label: "Reach",
    zones: ["Deep-cut territory", "A little buzz", "Crowd-tested only"],
    between: (value: number, min: number, max: number) =>
      `${value}%, ${min}–${max} listeners`,
    atLeast: (value: number, listeners: number) =>
      `${value}%, at least ${listeners} listeners`,
  },
  genres: {
    search: "Find a genre",
    placeholder: "Search genres, like jazz or shoegaze",
    noMatch: "No genres match that.",
    none: "No genres to pick from right now.",
    noChildren: "No subgenres.",
    families: "Genre families",
    loners: "Standalone genres",
    uniqueNote:
      "Genres with no parent or subgenres in the catalog. Unique is just a bucket, not a genre.",
    expand: (name: string) => `Expand ${name}`,
    collapse: (name: string) => `Collapse ${name}`,
    childOf: (parent: string) => `Subgenre of ${parent}`,
    partOf: (parents: string[]) => `Part of ${parents.join(" · ")}`,
    family: "Genre family · open it for subgenres",
    standalone: "Standalone genre",
    loadMore: "Load more",
    loadingAll: "Loading every genre…",
    loading: "Loading…",
    searching: "Searching…",
    retry: "Try again",
    like: "Like",
    likeName: (name: string) => `Like ${name}`,
    dislike: "Not for me",
    dislikeName: (name: string) => `Not into ${name}`,
  },
  chips: {
    album: (id: string) => `Album ${id}`,
    genre: "Picked genre",
    avoid: "Skip",
    special: "Really love it",
    specialName: (name: string) => `Really love ${name}`,
    focused: "Focus this time",
    focusedName: (name: string) => `Focus on ${name} this time`,
    remove: "Remove",
    removeName: (name: string) => `Remove ${name}`,
  },
  albums: {
    search: "Find an album",
    placeholder: "Search by artist or album",
    full: (max: number) => `You can pick up to ${max} albums.`,
    hint: "Type an artist or album and the results line up like a wall of records.",
    searching: "Searching…",
    retry: "Search again",
    noMatch: "No matches.",
    results: "Album search results",
    added: (name: string) => `Added ${name}`,
    add: (name: string) => `Add ${name}`,
    yearUnknown: "Year unknown",
  },
  search: { clear: "Clear search" },
  waiting: {
    label: "Finding albums",
    settling: "Got 'em",
    queued: "In line",
    working: "Digging",
    soon: "Coming right up",
    queuedFor: (clock: string) =>
      `Starts on its own when it's your turn · ${clock}`,
    waited: (clock: string) => `Waiting ${clock}`,
  },
  deck: {
    label: "Recommendations",
    exhausted: "That's every candidate this time",
    noMatch: "Couldn't find a good match this time",
    failed: "This round didn't finish",
    done: "That's the whole set",
    basic: "Basic picks · personalization didn't finish this time",
    feelings: "How it landed",
    like: "Love it!",
    meh: "Meh",
    heard: "Heard it",
    undoHeard: "Tap again to undo",
    heardKept: "Marked as heard",
    markHeard: "Mark as heard and we won't suggest it again",
    supplying: "Finding one more",
    next: "What's next",
    unhappy: "Not feeling it?",
    rethink: "Try a different idea",
    same: "Same request",
    more: "Hit me with more",
    rescreen: "Re-screen with the original request",
    prev: "Previous",
    forward: "Next",
    position: "Position",
    nth: (n: number) => `Album ${n}`,
  },
  review: {
    short: "Quick take",
    open: "Read the full review",
    close: "Hide the full review",
    writing: "Writing the full review",
    unavailable: "Full review unavailable",
    detail: (text: string) => `: ${text}`,
    regenerate: "Write it again",
    again: "Try again",
    failures: {
      FAILED: "Couldn't write it",
      TIMED_OUT: "Timed out",
      INTERRUPTED: "Got interrupted",
      CANCELLED: "Cancelled",
      BUDGET_EXHAUSTED: "Out of budget for this round",
    },
    progress: (label: string | null) =>
      `${label ?? "Review"} playback position, draggable`,
    scrub: "Drag or use the arrow keys to move through the text",
  },
  cover: { alt: (title: string) => `${title} cover` },
  wave: {
    tags: "Catalog genre tags",
    fallback: "A genre the music catalog tagged this album with. Just a hint.",
  },
  states: {
    waiting:
      "Waiting for a free slot. We'll pick it up as soon as it's your turn.",
    running: "Finding albums for you…",
    basic:
      "Personalization didn't finish this time, so here are some basic picks to start with.",
    IDLE: "",
    SUCCEEDED: "Your set is ready.",
    PARTIAL:
      "We found fewer solid picks than we were aiming for. Start with these.",
    NO_MATCH: "Nothing quite fits yet. Try adding a bit more detail.",
    FAILED:
      "This round didn't finish. Tweak your request or try again in a bit.",
    INTERRUPTED:
      "The round was interrupted. Anything already found is still here.",
  },
  messages: {
    "session.expired":
      "Your last discovery expired, so here's a fresh page. Start whenever you're ready.",
    "session.restored": null,
    "recommendation.waiting":
      "Waiting for a free slot. We'll pick it up as soon as it's your turn.",
    "recommendation.busy":
      "Lots of people in line right now. Try again in a bit.",
    "recommendation.queue_timeout":
      "The wait timed out. You can start a new round anytime.",
    "recommendation.provider_limited":
      "The recommendation service is swamped. Try again in a bit.",
    "recommendation.final_exhausted":
      "That's every candidate for this request. Tweak it to keep exploring.",
    "recommendation.personalization_incomplete":
      "Personalization didn't finish this time, so here are some basic picks to start with.",
    "execution.interrupted": "This round was interrupted.",
    QUEUE_FULL: "Lots of people in line right now. Try again in a bit.",
    QUEUE_TIMEOUT: "The wait timed out. You can start a new round anytime.",
    PROVIDER_RATE_LIMITED:
      "The recommendation service is swamped. Try again in a bit.",
    EXECUTION_INTERRUPTED: "This round was interrupted.",
    EXECUTION_GONE: "This round was interrupted.",
    CATALOG_TIMEOUT:
      "The catalog search timed out. Try again; it doesn't mean there's no match.",
    CATALOG_UNAVAILABLE:
      "The catalog is unavailable right now. Try again in a bit.",
    CURATED_UNAVAILABLE:
      "No curated picks right now. Try adding genres, albums or a few more details.",
    POSITIVE_RECOMMENDATION_NOT_CONFIGURED:
      "Personalized picks aren't open yet. Try again later.",
    RECOMMENDATION_UNAVAILABLE:
      "The recommendation service is unavailable right now. Try again in a bit.",
    DEPENDENCY_UNAVAILABLE:
      "Something the recommendations rely on is down right now. Try again in a bit.",
    PREFERENCE_UNAVAILABLE:
      "We can't read requests right now. Try again in a bit.",
    MAINTENANCE: "We're doing some maintenance. Try again in a bit.",
    BUDGET_EXHAUSTED:
      "This round is out of budget. Tweak your request to start over.",
    SESSION_BUDGET_EXHAUSTED:
      "This round is out of budget. Tweak your request to start over.",
    ROUND_BUDGET_EXHAUSTED:
      "This round is out of budget. Tweak your request to start over.",
    UNKNOWN_GENRE:
      "Some genres aren't in the catalog anymore. Remove them and try again.",
    UNKNOWN_IDENTITY:
      "Some albums couldn't be verified in the catalog. Remove them and try again.",
    INVALID_PAYLOAD: "Something's off with the input. Check it and try again.",
    IDEMPOTENCY_CONFLICT:
      "That clashed with another action. Refresh and try again.",
    RESOURCE_UNAVAILABLE: "Can't load this right now. Refresh and try again.",
    LONG_REVIEW_BUSY:
      "Full reviews are backed up. Tap “Write it again” in a moment.",
    REVIEW_MATERIAL_INSUFFICIENT:
      "The sources read so far don't support a full review.",
    LONG_RESEARCH_LIMIT_REACHED:
      "This attempt reached its research limit. The full review is unfinished.",
    LONG_RESEARCH_FAILED: "The review couldn't load the extra sources.",
    MODEL_FAILED:
      "The model didn't finish this one. You can retry it yourself.",
    JSON_PARSE_FAILED:
      "The model sent back something malformed, so the result couldn't be read.",
    SCHEMA_VALIDATION_FAILED: "The model's reply didn't pass the format check.",
    RESEARCH_FAILED:
      "The source check hit a technical error. That doesn't mean the material fell short.",
    NETWORK_UNCERTAIN:
      "The connection dropped, so we're not sure that went through. Refresh first, or retry the same action.",
    INVALID_RESPONSE:
      "The service sent back something we didn't recognize. Refresh in a bit.",
    SESSION_NOT_READY: "Not connected yet. Please reconnect.",
    UNEXPECTED_STATUS:
      "The service is unavailable right now. Refresh in a bit.",
    REVISION_CONFLICT:
      "Your results changed in another tab. Refresh before doing anything else.",
    FORBIDDEN: "Your session ended. Reload the page to start over.",
    UNAUTHENTICATED: "Your session ended. Reload the page to start over.",
    EXPIRED: "Your last discovery expired. Reconnect to start again.",
    UNDO_UNAVAILABLE: "That one can't be undone anymore.",
    EXECUTOR_BUSY: "Still working on the last one. It'll carry on in a moment.",
  },
  genericFailure: "That didn't go through. Refresh and try again.",
};

export default en;
