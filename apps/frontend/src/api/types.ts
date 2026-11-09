// Product-facing view of the generated contract types. Field definitions live only in the
// shared schema; this module just names the pieces the page uses.
export type {
  AlbumChoice,
  AlbumSearchResult,
  BootstrapState,
  CardSlot,
  CatalogAlbum,
  DiscoveryInput,
  DiscoverySubmission,
  GenreNode,
  GenreSearchResult,
  Identity,
  InputIssue,
  LongReview,
  LongReviewView,
  ProductCard,
  ProductContract,
  ProductError,
  ProductState,
  ResolvedAlbum,
} from "../generated/types.ts";

import type {
  DiscoveryInput,
  LongReviewView,
  ProductState,
} from "../generated/types.ts";

export type LikedGenre = DiscoveryInput["liked_genres"][number];
export type Strength = LikedGenre["strength"];
export type Mode = DiscoveryInput["mode"];
export type RunState = ProductState["status"];
export type LongReviewStatus = LongReviewView["status"];
