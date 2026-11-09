-- Explicit output evidence for publication; unknown historical finish remains unverified.
ALTER TABLE discovery.invocation ADD COLUMN finish varchar(16);
-- Publication owns immutable candidate admission and mutable display state.
CREATE TABLE discovery.publication_round (
    session_id discovery.identifier NOT NULL,
    round_id discovery.identifier PRIMARY KEY,
    run_id discovery.identifier NOT NULL,
    target integer NOT NULL CHECK(target BETWEEN 1 AND 1000),
    bundle jsonb,
    policy jsonb,
    preference_resolution discovery.identifier,
    audience_binding jsonb,
    status varchar(24) NOT NULL DEFAULT 'RUNNING',
    analysis_mode varchar(16),
    supply_state varchar(24) NOT NULL DEFAULT 'SUPPLYING',
    reason varchar(160),
    input_issues jsonb NOT NULL DEFAULT '[]',
    FOREIGN KEY(session_id,round_id) REFERENCES discovery.recommendation_round(session_id,id) ON DELETE CASCADE
);
ALTER TABLE discovery.round_candidate ADD COLUMN shown boolean NOT NULL DEFAULT false,
    ADD COLUMN heard boolean NOT NULL DEFAULT false, ADD COLUMN display_rank integer;
ALTER TABLE discovery.delivery_receipt ALTER COLUMN id TYPE varchar(256);
REVOKE ALL ON discovery.publication_round FROM PUBLIC;
