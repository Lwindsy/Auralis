package io.auralis.discovery.adapters;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.auralis.discovery.api.CuratedContent;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/** Validates the entire offline package before writing independent album/review/source files. */
public final class CuratedContentFiles {
    public static final List<String> TITLES =
            List.of(
                    "No Bad Memories",
                    "Endtroducing.....",
                    "U",
                    "Laughing Stock",
                    "Imaginal Disk",
                    "Sometimes I Might Be Introvert",
                    "Moon Safari",
                    "Cosmogramma",
                    "Voodoo",
                    "Sign o’ the Times");
    public static final List<String> ARTISTS =
            List.of(
                    "The Avalanches",
                    "DJ Shadow",
                    "underscores",
                    "Talk Talk",
                    "Magdalena Bay",
                    "Little Simz",
                    "Air",
                    "Flying Lotus",
                    "D'Angelo",
                    "Prince");
    public static final List<String> KEYS =
            List.of(
                    "01-no-bad-memories",
                    "02-endtroducing",
                    "03-u",
                    "04-laughing-stock",
                    "05-imaginal-disk",
                    "06-sometimes-i-might-be-introvert",
                    "07-moon-safari",
                    "08-cosmogramma",
                    "09-voodoo",
                    "10-sign-o-the-times");
    private static final ObjectMapper JSON =
            JsonMapper.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                    .build();
    private static final Pattern MARKER = Pattern.compile("\\[(R[1-9][0-9]*)]");

    private CuratedContentFiles() {}

    public static CuratedContent read(Path input) throws IOException {
        if (Files.size(input) > 4_000_000) throw invalid("PACKAGE_TOO_LARGE");
        var content = JSON.readValue(Files.readAllBytes(input), CuratedContent.class);
        validate(content);
        return content;
    }

    public static void validate(CuratedContent content) {
        if (content == null
                || !"auralis-curated-v1".equals(content.format_version())
                || content.albums() == null
                || content.albums().size() != KEYS.size()) throw invalid("PACKAGE_FORMAT");
        var ids = new HashSet<String>();
        for (int i = 0; i < KEYS.size(); i++) {
            var album = content.albums().get(i);
            if (album == null || !KEYS.get(i).equals(album.key())) throw invalid("ALBUM_ORDER");
            text(album.title(), 1024);
            text(album.artist(), 1024);
            if (!normalized(album.title()).equals(normalized(TITLES.get(i)))
                    || !normalized(album.artist()).equals(normalized(ARTISTS.get(i))))
                throw invalid("FIXED_LIST_IDENTITY");
            if (album.release_year() != null
                    && (album.release_year() < 1000 || album.release_year() > 9999))
                throw invalid("YEAR");
            if (album.musicbrainz_release_group_id() != null) {
                String id = album.musicbrainz_release_group_id();
                if (!UUID.fromString(id).toString().equals(id) || !ids.add(id))
                    throw invalid("ALBUM_ID");
            }
            if (album.identity_urls() == null
                    || album.genres() == null
                    || album.sources() == null
                    || album.missing() == null
                    || !Set.of("ready", "needs_research").contains(album.review_status()))
                throw invalid("ALBUM_FIELDS");
            album.identity_urls().forEach(CuratedContentFiles::url);
            album.genres().forEach(v -> text(v, 160));
            album.missing().forEach(v -> text(v, 2000));
            var refs = new HashSet<String>();
            for (var source : album.sources()) {
                if (source == null
                        || source.marker() == null
                        || !source.marker().matches("R[1-9][0-9]*")
                        || !refs.add(source.marker())) throw invalid("SOURCE_MARKER");
                url(source.url());
                text(source.title(), 2000);
                text(source.publisher(), 500);
                text(source.support(), 6000);
                LocalDate.parse(source.accessed_on());
            }
            boolean ready = album.review_status().equals("ready");
            review(album.short_review(), refs, ready, 2000);
            review(album.long_review(), refs, ready, 30000);
            if (ready
                    && (refs.isEmpty()
                            || !album.missing().isEmpty()
                            || album.identity_urls().isEmpty()))
                throw invalid("INCOMPLETE_READY_ALBUM");
            if (!ready && album.missing().isEmpty()) throw invalid("MISSING_RESEARCH_REASON");
        }
    }

    private static void review(
            CuratedContent.Review review, Set<String> sources, boolean ready, int max) {
        if (review == null
                || review.text() == null
                || review.refs() == null
                || review.text().codePointCount(0, review.text().length()) > max)
            throw invalid("REVIEW_FIELDS");
        var refs = new HashSet<>(review.refs());
        if (refs.size() != review.refs().size() || !sources.containsAll(refs))
            throw invalid("REVIEW_REFS");
        var used = new HashSet<String>();
        var matcher = MARKER.matcher(review.text());
        while (matcher.find()) used.add(matcher.group(1));
        if (!used.equals(refs)) throw invalid("TEXT_REFS");
        if (ready && (review.text().isBlank() || refs.isEmpty())) throw invalid("EMPTY_REVIEW");
    }

    private static void text(String value, int max) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > max)
            throw invalid("TEXT");
    }

    private static void url(String value) {
        text(value, 4096);
        var uri = URI.create(value);
        if (!Set.of("https", "http").contains(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null) throw invalid("SOURCE_URL");
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("CURATED_" + reason);
    }

    private static String normalized(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    public static void importFile(Path input, Path destination) throws IOException {
        var content = read(input);
        // A completion marker is written last; interrupted imports are never loadable.
        Files.createDirectory(destination);
        for (var album : content.albums()) {
            var folder = Files.createDirectory(destination.resolve(album.key()));
            var metadata = JSON.valueToTree(album);
            ((com.fasterxml.jackson.databind.node.ObjectNode) metadata)
                    .remove(List.of("short_review", "long_review", "sources"));
            JSON.writerWithDefaultPrettyPrinter()
                    .writeValue(folder.resolve("album.json").toFile(), metadata);
            JSON.writerWithDefaultPrettyPrinter()
                    .writeValue(folder.resolve("short-review.json").toFile(), album.short_review());
            JSON.writerWithDefaultPrettyPrinter()
                    .writeValue(folder.resolve("long-review.json").toFile(), album.long_review());
            JSON.writerWithDefaultPrettyPrinter()
                    .writeValue(folder.resolve("sources.json").toFile(), album.sources());
        }
        Files.writeString(destination.resolve("format-version.txt"), content.format_version());
    }

    public static CuratedContent load(Path directory) throws IOException {
        if (!Files.readString(directory.resolve("format-version.txt")).equals("auralis-curated-v1"))
            throw invalid("PACKAGE_FORMAT");
        var root = JSON.createObjectNode().put("format_version", "auralis-curated-v1");
        var albums = root.putArray("albums");
        for (String key : KEYS) {
            var folder = directory.resolve(key);
            var album = JSON.readTree(Files.readAllBytes(folder.resolve("album.json")));
            if (!album.isObject()) throw invalid("ALBUM_FIELDS");
            var value = (com.fasterxml.jackson.databind.node.ObjectNode) album;
            for (String field : List.of("short_review", "long_review", "sources")) {
                String file =
                        field.equals("sources")
                                ? "sources.json"
                                : field.replace('_', '-') + ".json";
                var path = folder.resolve(file);
                if (Files.size(path) > 1_000_000) throw invalid("PACKAGE_TOO_LARGE");
                value.set(field, JSON.readTree(Files.readAllBytes(path)));
            }
            albums.add(value);
        }
        var content = JSON.treeToValue(root, CuratedContent.class);
        validate(content);
        return content;
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2)
            throw new IllegalArgumentException(
                    "Usage: CuratedContentFiles <input.json> <new-output-directory>");
        importFile(Path.of(args[0]), Path.of(args[1]));
        System.out.println("Imported 10 albums; editorial and catalogue approval still required.");
    }
}
