package io.auralisy.discovery.api;

import java.util.List;

/** Offline editorial input. Source declarations are not online tool or model receipts. */
public record CuratedContent(String format_version, List<Album> albums) {
    public record Review(String text, List<String> refs) {}

    public record Source(
            String marker,
            String url,
            String title,
            String publisher,
            String accessed_on,
            String support) {}

    public record Album(
            String key,
            String title,
            String artist,
            Integer release_year,
            String musicbrainz_release_group_id,
            List<String> identity_urls,
            List<String> genres,
            String review_status,
            Review short_review,
            Review long_review,
            List<Source> sources,
            List<String> missing) {}
}
