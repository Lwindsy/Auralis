package io.auralis.catalog.adapters;

import org.apache.lucene.analysis.*;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;

/** Shared Unicode tokenization for building and querying, with accent-insensitive matching. */
final class CatalogAnalyzer extends Analyzer {
    static String exact(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
                .strip()
                .toLowerCase(java.util.Locale.ROOT);
    }

    protected TokenStreamComponents createComponents(String field) {
        var tokenizer = new StandardTokenizer();
        return new TokenStreamComponents(
                tokenizer, new ASCIIFoldingFilter(new LowerCaseFilter(tokenizer)));
    }
}
