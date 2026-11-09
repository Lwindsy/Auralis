ALTER TABLE discovery.publication_round ADD COLUMN display_seq bigint NOT NULL DEFAULT 0 CHECK(display_seq>=0);
-- Each logical run has its own exclusion binding; old background reviews retain their binding.
CREATE TABLE discovery.publication_run_binding (
    session_id discovery.identifier NOT NULL,
    round_id discovery.identifier NOT NULL,
    run_id discovery.identifier PRIMARY KEY,
    binding jsonb NOT NULL,
    FOREIGN KEY(session_id,round_id,run_id) REFERENCES discovery.discovery_intent(session_id,round_id,id) ON DELETE CASCADE
);
INSERT INTO discovery.publication_run_binding(session_id,round_id,run_id,binding)
SELECT session_id,round_id,run_id,bundle->'binding' FROM discovery.publication_round WHERE bundle IS NOT NULL;
CREATE TABLE discovery.execution_command (
    id discovery.identifier PRIMARY KEY,
    execution_id discovery.identifier NOT NULL REFERENCES discovery.execution(id) ON DELETE CASCADE,
    payload jsonb NOT NULL,
    acknowledged boolean NOT NULL DEFAULT false
);
REVOKE ALL ON discovery.publication_run_binding,discovery.execution_command FROM PUBLIC;

ALTER TABLE discovery.long_review DROP CONSTRAINT long_review_session_id_round_id_generation_run_fkey;
ALTER TABLE discovery.long_review ADD CONSTRAINT long_review_generation_run
    FOREIGN KEY(session_id,round_id,generation_run) REFERENCES discovery.discovery_intent(session_id,round_id,id)
    ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED;
