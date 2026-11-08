package io.auralis.preference.api;

/** Safe codes only; input text and proposed music facts are never exception messages. */
public final class PreferenceRejected extends RuntimeException {
    private final String outcome;

    public PreferenceRejected(String code) {
        this(code, "FAILED");
    }

    public PreferenceRejected(String code, String outcome) {
        super(code);
        this.outcome = outcome;
    }

    public String outcome() {
        return outcome;
    }
}
