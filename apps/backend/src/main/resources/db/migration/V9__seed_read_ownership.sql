-- Evidence owns read identities and independently owned seed provenance.
CREATE TABLE discovery.read_ticket (
    read_id discovery.identifier PRIMARY KEY,
    session_id discovery.identifier NOT NULL,
    round_id discovery.identifier NOT NULL,
    execution_id discovery.identifier NOT NULL,
    candidate_id discovery.identifier NOT NULL,
    purpose varchar(32) NOT NULL,
    requested_url varchar(4096) NOT NULL,
    identity_revision discovery.digest NOT NULL,
    data_revision discovery.identifier NOT NULL,
    issued_at timestamptz NOT NULL,
    FOREIGN KEY(session_id,round_id,execution_id) REFERENCES discovery.execution(session_id,round_id,id) ON DELETE CASCADE
);
CREATE INDEX read_claim_round ON discovery.read_claim(round_id);
CREATE TABLE discovery.seed_read_binding (
    session_id discovery.identifier NOT NULL REFERENCES discovery.session(id) ON DELETE CASCADE,
    read_id discovery.identifier NOT NULL,
    seed_id discovery.identifier NOT NULL,
    source_read_id discovery.identifier,
    source_session_id discovery.identifier,
    receipt discovery.document NOT NULL,
    PRIMARY KEY(session_id,read_id), UNIQUE(read_id)
);
INSERT INTO discovery.seed_read_binding
SELECT DISTINCT s.session_id,s.read_id,s.seed_id,s.source_read_id,NULL,r.receipt
FROM discovery.seed_evidence s JOIN discovery.read_receipt r ON r.session_id=s.session_id AND r.id=s.read_id;
DO $$ DECLARE c record; BEGIN
 FOR c IN SELECT conname FROM pg_constraint WHERE conrelid='discovery.seed_evidence'::regclass AND confrelid='discovery.read_receipt'::regclass LOOP
  EXECUTE format('ALTER TABLE discovery.seed_evidence DROP CONSTRAINT %I',c.conname);
 END LOOP;
END $$;
ALTER TABLE discovery.seed_evidence ADD CONSTRAINT seed_owned_read
 FOREIGN KEY(session_id,read_id) REFERENCES discovery.seed_read_binding(session_id,read_id);
REVOKE ALL ON discovery.read_ticket, discovery.seed_read_binding FROM PUBLIC;
