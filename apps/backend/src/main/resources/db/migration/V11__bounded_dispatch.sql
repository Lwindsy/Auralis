-- Discovery owns dispatch snapshots and command receipts. Content follows session erasure.
ALTER TABLE discovery.discovery_intent
    ADD COLUMN dispatch_plan jsonb,
    ADD COLUMN scope_snapshot jsonb,
    ADD COLUMN demand_ids jsonb NOT NULL DEFAULT '[]',
    ADD COLUMN technical_deadline timestamptz,
    ADD COLUMN error_code varchar(64);
ALTER TABLE discovery.execution
    ADD COLUMN start_payload jsonb,
    ADD COLUMN dispatch_attempts integer NOT NULL DEFAULT 0 CHECK(dispatch_attempts BETWEEN 0 AND 2),
    ADD COLUMN observed_seq bigint NOT NULL DEFAULT -1,
    ADD COLUMN cancel_reason varchar(32);
CREATE TABLE discovery.dispatch_command (
    subject_id discovery.identifier NOT NULL,
    session_id discovery.identifier NOT NULL,
    operation discovery.identifier NOT NULL,
    command_id discovery.identifier NOT NULL,
    request_digest discovery.digest NOT NULL,
    run_id discovery.identifier NOT NULL REFERENCES discovery.discovery_intent(id) ON DELETE CASCADE,
    PRIMARY KEY(subject_id,operation,command_id),
    FOREIGN KEY(subject_id,session_id) REFERENCES discovery.session(subject_id,id) ON DELETE CASCADE
);
REVOKE ALL ON discovery.dispatch_command FROM PUBLIC;
