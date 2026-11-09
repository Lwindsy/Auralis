package io.auralis.catalog.adapters;

import static io.auralis.catalog.application.CatalogJson.JSON;

import io.auralis.catalog.api.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.*;

/** Immutable full-directory index. One reader per process; no per-user index or upstream HTTP. */
public final class LuceneCatalogSearch implements CatalogSearchPort, AutoCloseable {
    private final Directory directory;
    private final DirectoryReader reader;
    private final IndexSearcher searcher;
    private final CatalogAnalyzer analyzer = new CatalogAnalyzer();
    private final Semaphore slots = new Semaphore(5, true);
    private final String revision;
    private final java.util.function.Supplier<Map<String, Long>> popularity;

    public LuceneCatalogSearch(Path path, String expectedManifest) throws IOException {
        this(path, expectedManifest, Map::of);
    }

    public LuceneCatalogSearch(
            Path path,
            String expectedManifest,
            java.util.function.Supplier<Map<String, Long>> popularity)
            throws IOException {
        this.popularity = popularity;
        if (!expectedManifest.matches("[0-9a-f]{64}")
                || !Files.readString(path.resolve("manifest.sha256")).equals(expectedManifest))
            throw new IOException("INDEX_NOT_ADMITTED");
        directory = FSDirectory.open(path);
        DirectoryReader opened = null;
        try {
            opened = DirectoryReader.open(directory);
            var meta = opened.getIndexCommit().getUserData();
            if (!"2".equals(meta.get("format"))
                    || !expectedManifest.equals(meta.get("manifest"))
                    || opened.numDocs() != Long.parseLong(meta.getOrDefault("rows", "-1")))
                throw new IOException("INDEX_NOT_ADMITTED");
            reader = opened;
            searcher = new IndexSearcher(reader);
            revision = expectedManifest;
        } catch (Exception failure) {
            if (opened != null) opened.close();
            directory.close();
            throw new IOException("INDEX_NOT_ADMITTED", failure);
        }
    }

    public String revision() {
        return revision;
    }

    @Override
    public Page search(String text, int offset, int limit) {
        if (text == null
                || text.isBlank()
                || text.codePointCount(0, text.length()) > 200
                || offset < 0
                || offset > 10000
                || limit < 1
                || limit > 50) throw new CatalogFailure(400, "INVALID_PAYLOAD");
        if (!slots.tryAcquire()) throw new CatalogFailure(429, "CATALOG_BUSY");
        try {
            List<String> terms = new ArrayList<>();
            try (var stream = analyzer.tokenStream("text", text)) {
                var term = stream.addAttribute(CharTermAttribute.class);
                stream.reset();
                while (stream.incrementToken()) terms.add(term.toString());
                stream.end();
            }
            var query = new BooleanQuery.Builder();
            for (int i = 0; i < terms.size(); i++) {
                String word = terms.get(i);
                Query token = new TermQuery(new Term("text", word));
                if (i == terms.size() - 1 && (terms.size() > 1 || word.length() >= 3))
                    token = new PrefixQuery(new Term("text", word));
                query.add(token, BooleanClause.Occur.MUST);
            }
            var title = new PhraseQuery.Builder();
            var artist = new PhraseQuery.Builder();
            for (int i = 0; i < terms.size(); i++) {
                title.add(new Term("title", terms.get(i)), i);
                artist.add(new Term("artist", terms.get(i)), i);
            }
            if (!terms.isEmpty()) {
                query.add(
                        new BoostQuery(new ConstantScoreQuery(title.build()), 4),
                        BooleanClause.Occur.SHOULD);
                // An artist-name query should surface that artist's catalogue before a
                // coincidentally named album. Keep this bonus independent of term rarity.
                query.add(
                        new BoostQuery(new ConstantScoreQuery(artist.build()), 16),
                        BooleanClause.Occur.SHOULD);
            }
            Query effective =
                    terms.isEmpty()
                            ? new TermQuery(new Term("exact", CatalogAnalyzer.exact(text)))
                            : query.build();
            var counts = popularity.get();
            var hits =
                    searcher.search(
                            effective,
                            offset + limit + 1,
                            new Sort(
                                    counts.isEmpty()
                                            ? SortField.FIELD_SCORE
                                            : new PopularityScore(counts).getSortField(true),
                                    new SortField("sort_id", SortField.Type.STRING)));
            var result = new ArrayList<Album>();
            for (int i = offset; i < Math.min(hits.scoreDocs.length, offset + limit); i++)
                result.add(album(hits.scoreDocs[i].doc));
            return new Page(
                    result,
                    hits.scoreDocs.length > offset + limit && offset + limit <= 10000
                            ? offset + limit
                            : null);
        } catch (IOException failure) {
            throw new CatalogFailure(503, "CATALOG_UNAVAILABLE");
        } finally {
            slots.release();
        }
    }

    private static final class PopularityScore extends DoubleValuesSource {
        private final Map<String, Long> counts;

        PopularityScore(Map<String, Long> counts) {
            this.counts = counts;
        }

        @Override
        public DoubleValues getValues(LeafReaderContext context, DoubleValues scores)
                throws IOException {
            var ids = DocValues.getSorted(context.reader(), "sort_id");
            return new DoubleValues() {
                private long listeners;

                public boolean advanceExact(int doc) throws IOException {
                    listeners =
                            ids.advanceExact(doc)
                                    ? counts.getOrDefault(
                                            ids.lookupOrd(ids.ordValue()).utf8ToString(), 0L)
                                    : 0;
                    return scores.advanceExact(doc);
                }

                public double doubleValue() throws IOException {
                    return scores.doubleValue() + 8 * Math.log1p(listeners);
                }
            };
        }

        @Override
        public boolean needsScores() {
            return true;
        }

        @Override
        public DoubleValuesSource rewrite(IndexSearcher searcher) {
            return this;
        }

        @Override
        public boolean isCacheable(LeafReaderContext context) {
            return false;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof PopularityScore score && counts == score.counts;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(counts);
        }

        @Override
        public String toString() {
            return "text relevance plus log audience";
        }
    }

    @Override
    public Album resolve(UUID id) {
        try {
            var hits = searcher.search(new TermQuery(new Term("id", id.toString())), 2);
            if (hits.scoreDocs.length == 0) throw new CatalogFailure(404, "CATALOG_NOT_FOUND");
            if (hits.scoreDocs.length != 1) throw new CatalogFailure(503, "AMBIGUOUS_CATALOG_ID");
            return album(hits.scoreDocs[0].doc);
        } catch (IOException failure) {
            throw new CatalogFailure(503, "CATALOG_UNAVAILABLE");
        }
    }

    private Album album(int doc) throws IOException {
        var row = JSON.readTree(searcher.storedFields().document(doc).get("album"));
        var genres = new ArrayList<CatalogQuery.Genre>();
        for (var g : row.path("genres"))
            genres.add(
                    new CatalogQuery.Genre(g.path("genre_id").asText(), g.path("name").asText()));
        return new Album(
                UUID.fromString(row.path("id").asText()),
                row.path("title").asText(),
                row.path("artist").asText(),
                row.path("year").isNull() ? null : row.path("year").asInt(),
                row.path("type").isNull() ? null : row.path("type").asText(),
                genres,
                0);
    }

    @Override
    public void close() throws IOException {
        analyzer.close();
        reader.close();
        directory.close();
    }
}
