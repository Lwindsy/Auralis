-- Online state. Existing catalog/statistics/ingestion migrations are immutable.
CREATE SCHEMA discovery;
REVOKE ALL ON SCHEMA discovery FROM PUBLIC;
CREATE DOMAIN discovery.identifier AS varchar(160) CHECK (length(VALUE) > 0);
CREATE DOMAIN discovery.digest AS varchar(71) CHECK (VALUE ~ '^sha256:[0-9a-f]{64}$');
-- Storage ceiling, not a replacement for the authoritative message schema.
CREATE DOMAIN discovery.document AS jsonb
    CHECK (jsonb_typeof(VALUE) = 'object' AND octet_length(VALUE::text) <= 16777216);

-- session owns subjects, sessions, commands, heard albums and deletion work.
CREATE TABLE discovery.subject (
    id varchar(160) PRIMARY KEY CHECK (length(id) > 0),
    credential_digest varchar(71) NOT NULL UNIQUE CHECK (credential_digest ~ '^sha256:[0-9a-f]{64}$'),
    lifecycle varchar(16) NOT NULL CHECK (lifecycle IN ('ACTIVE','REVOKED','EXPIRED')),
    epoch bigint NOT NULL DEFAULT 0 CHECK (epoch >= 0),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    current_session_id varchar(160),
    last_user_action timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at > last_user_action)
);
CREATE INDEX subject_expiry ON discovery.subject(expires_at,id);
CREATE TABLE discovery.session (
    id varchar(160) PRIMARY KEY CHECK (length(id) > 0),
    subject_id varchar(160) NOT NULL REFERENCES discovery.subject(id) ON DELETE CASCADE,
    input_revision bigint NOT NULL CHECK (input_revision >= 1),
    raw_input jsonb NOT NULL CHECK (jsonb_typeof(raw_input) = 'object' AND octet_length(raw_input::text) <= 16777216),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    epoch bigint NOT NULL DEFAULT 0 CHECK (epoch >= 0),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE','SUPERSEDED','DELETED','EXPIRED')),
    current_round_id varchar(160),
    created_at timestamptz NOT NULL,
    last_user_action timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at > last_user_action),
    UNIQUE(subject_id,id), UNIQUE(subject_id,input_revision)
);
CREATE UNIQUE INDEX one_current_session ON discovery.session(subject_id) WHERE status='ACTIVE';
CREATE INDEX session_subject_created ON discovery.session(subject_id,created_at DESC,id);
CREATE INDEX session_expiry ON discovery.session(expires_at,id);
ALTER TABLE discovery.subject ADD CONSTRAINT subject_current_session
    FOREIGN KEY(id,current_session_id) REFERENCES discovery.session(subject_id,id)
    DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE discovery.heard_album (
    subject_id discovery.identifier REFERENCES discovery.subject(id) ON DELETE CASCADE,
    release_group_id uuid NOT NULL,
    heard_at timestamptz NOT NULL,
    PRIMARY KEY(subject_id,release_group_id)
);
CREATE TABLE discovery.command_receipt (
    subject_id discovery.identifier REFERENCES discovery.subject(id) ON DELETE CASCADE,
    operation discovery.identifier NOT NULL,
    command_id discovery.identifier NOT NULL,
    request_digest discovery.digest NOT NULL,
    result_ref discovery.document NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY(subject_id,operation,command_id)
);
CREATE TABLE discovery.deletion_job (
    id discovery.identifier PRIMARY KEY,
    subject_id discovery.identifier NOT NULL REFERENCES discovery.subject(id) ON DELETE CASCADE,
    session_id discovery.identifier,
    barrier_epoch bigint NOT NULL CHECK (barrier_epoch >= 0),
    state varchar(16) NOT NULL CHECK (state IN ('PENDING','RUNNING','FAILED','COMPLETED')),
    step discovery.identifier NOT NULL,
    cursor discovery.identifier,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL,
    FOREIGN KEY(subject_id,session_id) REFERENCES discovery.session(subject_id,id) ON DELETE CASCADE
);
CREATE INDEX deletion_pending ON discovery.deletion_job(state,next_attempt_at,id);

-- discovery owns rounds, runs (accepted intents), executions and fixed slots.
CREATE TABLE discovery.recommendation_round (
    id discovery.identifier PRIMARY KEY,
    session_id discovery.identifier NOT NULL REFERENCES discovery.session(id) ON DELETE CASCADE,
    round_seq integer NOT NULL CHECK (round_seq BETWEEN 1 AND 5),
    configuration discovery.document NOT NULL,
    recall_binding discovery.document NOT NULL,
    candidate_digest discovery.digest,
    state discovery.identifier NOT NULL,
    deadline timestamptz NOT NULL,
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    UNIQUE(session_id,round_seq), UNIQUE(session_id,id)
);
ALTER TABLE discovery.session ADD CONSTRAINT session_current_round
    FOREIGN KEY(id,current_round_id) REFERENCES discovery.recommendation_round(session_id,id)
    DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE discovery.discovery_intent (
    id discovery.identifier PRIMARY KEY, -- the externally referenced run_id
    subject_id discovery.identifier NOT NULL,
    session_id discovery.identifier NOT NULL,
    round_id discovery.identifier NOT NULL,
    operation discovery.identifier NOT NULL,
    input_revision bigint NOT NULL CHECK (input_revision >= 1),
    request_digest discovery.digest NOT NULL,
    accepted_at timestamptz NOT NULL,
    queue_deadline timestamptz NOT NULL CHECK (queue_deadline > accepted_at),
    status varchar(16) NOT NULL CHECK (status IN ('WAITING','DISPATCHING','RUNNING','SUCCEEDED','DEGRADED','NO_MATCH','FAILED','INTERRUPTED','CANCELLED','EXPIRED','SUPERSEDED')),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    FOREIGN KEY(subject_id,session_id) REFERENCES discovery.session(subject_id,id) ON DELETE CASCADE,
    FOREIGN KEY(session_id,round_id) REFERENCES discovery.recommendation_round(session_id,id) ON DELETE CASCADE,
    UNIQUE(session_id,round_id,id)
);
CREATE UNIQUE INDEX one_active_intent ON discovery.discovery_intent(subject_id)
    WHERE status IN ('WAITING','DISPATCHING','RUNNING');
CREATE INDEX intent_fifo ON discovery.discovery_intent(status,accepted_at,id);
CREATE INDEX intent_session ON discovery.discovery_intent(session_id,id);
CREATE TABLE discovery.runtime_control (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    coordinator_boot discovery.identifier,
    python_boot discovery.identifier,
    maintenance boolean NOT NULL DEFAULT true,
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0)
);
INSERT INTO discovery.runtime_control(singleton) VALUES(true);
CREATE TABLE discovery.execution_slot (
    slot_no smallint PRIMARY KEY CHECK (slot_no BETWEEN 1 AND 3),
    state varchar(16) NOT NULL CHECK (state IN ('FREE','RESERVED','ACTIVE','RELEASING')),
    execution_id discovery.identifier UNIQUE,
    CHECK ((state='FREE') = (execution_id IS NULL))
);
INSERT INTO discovery.execution_slot(slot_no,state) VALUES (1,'FREE'),(2,'FREE'),(3,'FREE');
CREATE TABLE discovery.execution (
    id discovery.identifier PRIMARY KEY,
    session_id discovery.identifier NOT NULL,
    round_id discovery.identifier NOT NULL,
    run_id discovery.identifier NOT NULL,
    coordinator_boot discovery.identifier NOT NULL,
    python_boot discovery.identifier NOT NULL,
    slot_no smallint NOT NULL REFERENCES discovery.execution_slot(slot_no),
    state varchar(16) NOT NULL CHECK (state IN ('RESERVED','ACCEPTED','RUNNING','CLOSING','CLOSED','FAILED','CANCELLED')),
    sent_at timestamptz,
    physical_released_at timestamptz,
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    FOREIGN KEY(session_id,round_id,run_id) REFERENCES discovery.discovery_intent(session_id,round_id,id) ON DELETE CASCADE,
    UNIQUE(session_id,round_id,id), UNIQUE(slot_no,id),
    CHECK (physical_released_at IS NULL OR state IN ('CLOSED','FAILED','CANCELLED'))
);
CREATE UNIQUE INDEX execution_physical_slot ON discovery.execution(slot_no) WHERE physical_released_at IS NULL;
CREATE INDEX execution_run_boot ON discovery.execution(run_id,python_boot,coordinator_boot);
CREATE INDEX execution_round ON discovery.execution(round_id,id);
ALTER TABLE discovery.execution_slot ADD CONSTRAINT slot_execution
    FOREIGN KEY(slot_no,execution_id) REFERENCES discovery.execution(slot_no,id)
    DEFERRABLE INITIALLY DEFERRED;

-- preference owns effective, validated snapshots; no second wire DTO definition.
CREATE TABLE discovery.effective_preference (
    session_id discovery.identifier REFERENCES discovery.session(id) ON DELETE CASCADE,
    resolution_id discovery.identifier NOT NULL,
    input_digest discovery.digest NOT NULL,
    reference_revision discovery.identifier NOT NULL,
    parser_revision discovery.identifier NOT NULL,
    effective_digest discovery.digest NOT NULL,
    result discovery.document NOT NULL,
    PRIMARY KEY(session_id,resolution_id)
);

-- catalog owns the version-bound audience projection. No core statistic scan.
CREATE TABLE discovery.audience_snapshot (
    id discovery.identifier PRIMARY KEY,
    dataset_id text NOT NULL CHECK (length(dataset_id) BETWEEN 1 AND 4096),
    listening_window varchar(160) NOT NULL CHECK (length(listening_window) > 0),
    sample_listeners bigint NOT NULL CHECK (sample_listeners > 0),
    manifest_digest discovery.digest NOT NULL UNIQUE,
    snapshot_fingerprint varchar(64) NOT NULL CHECK (snapshot_fingerprint ~ '^[0-9a-f]{64}$'),
    recall_binding discovery.document NOT NULL,
    active boolean NOT NULL DEFAULT false,
    UNIQUE(id,listening_window,sample_listeners)
);
CREATE UNIQUE INDEX one_active_audience ON discovery.audience_snapshot(active) WHERE active;
CREATE TABLE discovery.audience_count (
    snapshot_id discovery.identifier NOT NULL,
    listening_window varchar(160) NOT NULL,
    sample_listeners bigint NOT NULL,
    release_group_id uuid NOT NULL,
    listeners bigint NOT NULL CHECK (listeners BETWEEN 0 AND sample_listeners),
    PRIMARY KEY(snapshot_id,listening_window,release_group_id),
    FOREIGN KEY(snapshot_id,listening_window,sample_listeners) REFERENCES discovery.audience_snapshot(id,listening_window,sample_listeners) ON DELETE CASCADE
);

-- publication owns candidate disposition and display material; catalog verifies identity upstream.
CREATE TABLE discovery.round_candidate (
    session_id discovery.identifier NOT NULL,
    round_id discovery.identifier NOT NULL,
    candidate_id discovery.identifier NOT NULL,
    release_group_id uuid NOT NULL,
    rank integer NOT NULL CHECK (rank >= 0),
    identity_binding discovery.document NOT NULL,
    audience_binding discovery.document NOT NULL,
    screening discovery.document,
    disposition discovery.identifier NOT NULL,
    PRIMARY KEY(round_id,candidate_id), UNIQUE(round_id,release_group_id),
    UNIQUE(session_id,round_id,candidate_id),
    FOREIGN KEY(session_id,round_id) REFERENCES discovery.recommendation_round(session_id,id) ON DELETE CASCADE
);
CREATE INDEX candidate_supply ON discovery.round_candidate(round_id,disposition,rank,candidate_id);
CREATE INDEX candidate_session ON discovery.round_candidate(session_id,round_id);

-- evidence owns read claims, receipts and finite seed excerpts.
CREATE TABLE discovery.read_claim (
    session_id discovery.identifier NOT NULL,
    round_id discovery.identifier NOT NULL,
    execution_id discovery.identifier NOT NULL,
    read_id discovery.identifier NOT NULL,
    url_digest discovery.digest NOT NULL,
    claimed_at timestamptz NOT NULL,
    PRIMARY KEY(execution_id,read_id,url_digest),
    FOREIGN KEY(session_id,round_id,execution_id) REFERENCES discovery.execution(session_id,round_id,id) ON DELETE CASCADE
);
CREATE TABLE discovery.read_receipt (
    id discovery.identifier PRIMARY KEY,
    session_id discovery.identifier NOT NULL,
    round_id discovery.identifier NOT NULL,
    execution_id discovery.identifier NOT NULL,
    candidate_id discovery.identifier NOT NULL,
    release_group_id uuid NOT NULL,
    final_url varchar(4096),
    read_at timestamptz NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('READ_OK','READ_FAILED','BLOCKED_BY_CONTROL')),
    content_digest discovery.digest,
    receipt discovery.document NOT NULL,
    UNIQUE(session_id,id),
    FOREIGN KEY(session_id,round_id,execution_id) REFERENCES discovery.execution(session_id,round_id,id) ON DELETE CASCADE,
    CHECK (status <> 'READ_OK' OR (final_url IS NOT NULL AND content_digest IS NOT NULL))
);
CREATE INDEX read_session_album ON discovery.read_receipt(session_id,release_group_id,id);
CREATE TABLE discovery.seed_evidence (
    session_id discovery.identifier REFERENCES discovery.session(id) ON DELETE CASCADE,
    seed_id discovery.identifier NOT NULL,
    identity_revision discovery.identifier NOT NULL,
    data_revision discovery.identifier NOT NULL,
    read_id discovery.identifier,
    source_read_id discovery.identifier, -- provenance only; deleting the source must not delete a valid derived copy
    content_digest discovery.digest,
    read_at timestamptz,
    expires_at timestamptz NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('READ_AVAILABLE','NO_USABLE_PAGE')),
    evidence discovery.document NOT NULL,
    PRIMARY KEY(session_id,seed_id,identity_revision),
    FOREIGN KEY(session_id,read_id) REFERENCES discovery.read_receipt(session_id,id),
    CHECK (status <> 'READ_AVAILABLE' OR (read_id IS NOT NULL AND content_digest IS NOT NULL AND read_at IS NOT NULL)),
    CHECK (read_at IS NULL OR expires_at > read_at)
);

-- budget rows survive content erasure with nullable personal ownership references.
CREATE TABLE discovery.budget_scope (
    id discovery.identifier PRIMARY KEY,
    session_id discovery.identifier REFERENCES discovery.session(id) ON DELETE SET NULL,
    parent_scope_id discovery.identifier REFERENCES discovery.budget_scope(id),
    cap_microusd bigint NOT NULL CHECK (cap_microusd >= 0),
    reserved_microusd bigint NOT NULL DEFAULT 0 CHECK (reserved_microusd >= 0),
    settled_microusd bigint NOT NULL DEFAULT 0 CHECK (settled_microusd >= 0),
    price_revision discovery.identifier NOT NULL,
    currency varchar(3) NOT NULL DEFAULT 'USD' CHECK (currency='USD'),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    UNIQUE(session_id,id),
    FOREIGN KEY(session_id,parent_scope_id) REFERENCES discovery.budget_scope(session_id,id),
    CHECK (parent_scope_id IS DISTINCT FROM id)
    -- Actual overspend must remain recordable; reservation uses a guarded conditional UPDATE.
);
CREATE INDEX budget_session ON discovery.budget_scope(session_id,id);
CREATE TABLE discovery.invocation (
    id varchar(256) PRIMARY KEY CHECK (length(id) > 0),
    session_id discovery.identifier REFERENCES discovery.session(id) ON DELETE SET NULL DEFERRABLE INITIALLY DEFERRED,
    round_id discovery.identifier REFERENCES discovery.recommendation_round(id) ON DELETE SET NULL DEFERRABLE INITIALLY DEFERRED,
    execution_id discovery.identifier REFERENCES discovery.execution(id) ON DELETE SET NULL DEFERRABLE INITIALLY DEFERRED,
    scope_id discovery.identifier NOT NULL REFERENCES discovery.budget_scope(id),
    lane varchar(16) NOT NULL CHECK (lane IN ('FOREGROUND','BACKGROUND')),
    purpose discovery.identifier NOT NULL,
    request_digest discovery.digest NOT NULL,
    reserved_microusd bigint NOT NULL CHECK (reserved_microusd >= 0),
    outcome discovery.identifier NOT NULL,
    send_consumed boolean NOT NULL DEFAULT false,
    price_binding discovery.document NOT NULL,
    UNIQUE(session_id,id),
    FOREIGN KEY(session_id,scope_id) REFERENCES discovery.budget_scope(session_id,id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY(session_id,round_id) REFERENCES discovery.recommendation_round(session_id,id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY(session_id,round_id,execution_id) REFERENCES discovery.execution(session_id,round_id,id) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX invocation_round_outcome ON discovery.invocation(round_id,outcome,id);
CREATE INDEX invocation_execution ON discovery.invocation(execution_id,id);
CREATE TABLE discovery.usage_receipt (
    invocation_id varchar(256) REFERENCES discovery.invocation(id),
    receipt_id varchar(256) NOT NULL CHECK (length(receipt_id) > 0),
    payload_digest discovery.digest NOT NULL,
    settlement_seq bigint NOT NULL CHECK (settlement_seq >= 0),
    cost_microusd bigint CHECK (cost_microusd >= 0),
    input_tokens bigint CHECK (input_tokens >= 0),
    output_tokens bigint CHECK (output_tokens >= 0),
    outcome discovery.identifier NOT NULL,
    PRIMARY KEY(invocation_id,receipt_id), UNIQUE(invocation_id,settlement_seq)
);

CREATE TABLE discovery.delivery_receipt (
    id discovery.identifier PRIMARY KEY,
    session_id discovery.identifier NOT NULL,
    round_id discovery.identifier NOT NULL,
    execution_id discovery.identifier NOT NULL,
    seq bigint NOT NULL CHECK (seq >= 0),
    payload_digest discovery.digest NOT NULL,
    result discovery.document NOT NULL,
    UNIQUE(execution_id,seq),
    FOREIGN KEY(session_id,round_id,execution_id) REFERENCES discovery.execution(session_id,round_id,id) ON DELETE CASCADE
);
CREATE TABLE discovery.recommendation_card (
    session_id discovery.identifier NOT NULL,
    round_id discovery.identifier NOT NULL,
    candidate_id discovery.identifier NOT NULL,
    content discovery.document NOT NULL,
    selection_basis varchar(16) NOT NULL CHECK (selection_basis IN ('PERSONALIZED','BASIC','CURATED')),
    state discovery.identifier NOT NULL,
    PRIMARY KEY(round_id,candidate_id), UNIQUE(session_id,round_id,candidate_id),
    FOREIGN KEY(session_id,round_id,candidate_id) REFERENCES discovery.round_candidate(session_id,round_id,candidate_id) ON DELETE CASCADE
);
CREATE TABLE discovery.card_slot (
    session_id discovery.identifier REFERENCES discovery.session(id) ON DELETE CASCADE,
    slot_id discovery.identifier NOT NULL,
    round_id discovery.identifier,
    candidate_id discovery.identifier,
    state varchar(16) NOT NULL CHECK (state IN ('READY','SUPPLYING','EMPTY','FAILED')),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    PRIMARY KEY(session_id,slot_id),
    FOREIGN KEY(session_id,round_id,candidate_id) REFERENCES discovery.recommendation_card(session_id,round_id,candidate_id),
    CHECK ((candidate_id IS NULL) = (round_id IS NULL)),
    CHECK ((state='READY') = (candidate_id IS NOT NULL))
);
CREATE UNIQUE INDEX one_candidate_slot ON discovery.card_slot(session_id,candidate_id) WHERE candidate_id IS NOT NULL;
CREATE TABLE discovery.long_review (
    session_id discovery.identifier NOT NULL,
    round_id discovery.identifier NOT NULL,
    candidate_id discovery.identifier NOT NULL,
    generation_run discovery.identifier NOT NULL,
    state discovery.identifier NOT NULL,
    content discovery.document,
    reason varchar(160),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    PRIMARY KEY(round_id,candidate_id),
    FOREIGN KEY(session_id,round_id,candidate_id) REFERENCES discovery.recommendation_card(session_id,round_id,candidate_id) ON DELETE CASCADE,
    FOREIGN KEY(session_id,round_id,generation_run) REFERENCES discovery.discovery_intent(session_id,round_id,id)
);

-- Defense in depth: neither a stale ORM entity nor direct SQL can reopen a barrier.
CREATE FUNCTION discovery.protect_session_barrier() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.subject_id <> OLD.subject_id OR NEW.input_revision <> OLD.input_revision
       OR NEW.revision <= OLD.revision OR NEW.epoch < OLD.epoch
       OR (OLD.status <> 'ACTIVE' AND NEW.status = 'ACTIVE')
       OR (OLD.status = 'DELETED' AND NEW.status <> 'DELETED')
       OR (OLD.status = 'EXPIRED' AND NEW.status NOT IN ('EXPIRED','DELETED'))
       OR (OLD.status <> 'ACTIVE' AND (NEW.raw_input IS DISTINCT FROM OLD.raw_input
           OR NEW.current_round_id IS DISTINCT FROM OLD.current_round_id
           OR NEW.expires_at <> OLD.expires_at OR NEW.last_user_action <> OLD.last_user_action))
       OR (NEW.status <> OLD.status AND NEW.epoch <= OLD.epoch) THEN
        RAISE EXCEPTION 'STALE_SESSION_OR_BARRIER' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER protect_session_barrier BEFORE UPDATE ON discovery.session
    FOR EACH ROW EXECUTE FUNCTION discovery.protect_session_barrier();
CREATE FUNCTION discovery.protect_subject_barrier() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.revision <= OLD.revision OR NEW.epoch < OLD.epoch
       OR (OLD.lifecycle <> 'ACTIVE' AND NEW.lifecycle <> OLD.lifecycle)
       OR (OLD.lifecycle <> 'ACTIVE' AND (NEW.current_session_id IS NOT NULL
           OR NEW.credential_digest <> OLD.credential_digest OR NEW.expires_at <> OLD.expires_at
           OR NEW.last_user_action <> OLD.last_user_action))
       OR (NEW.lifecycle <> OLD.lifecycle AND NEW.epoch <= OLD.epoch) THEN
        RAISE EXCEPTION 'STALE_SUBJECT_OR_BARRIER' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER protect_subject_barrier BEFORE UPDATE ON discovery.subject
    FOR EACH ROW EXECUTE FUNCTION discovery.protect_subject_barrier();

CREATE FUNCTION discovery.protect_run_terminal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.revision <= OLD.revision OR NEW.subject_id <> OLD.subject_id
       OR NEW.session_id <> OLD.session_id OR NEW.round_id <> OLD.round_id
       OR NEW.input_revision <> OLD.input_revision OR NEW.accepted_at <> OLD.accepted_at
       OR NEW.queue_deadline <> OLD.queue_deadline
       OR (OLD.status NOT IN ('WAITING','DISPATCHING','RUNNING') AND NEW.status <> OLD.status) THEN
        RAISE EXCEPTION 'STALE_RUN_OR_TERMINAL' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER protect_run_terminal BEFORE UPDATE ON discovery.discovery_intent
    FOR EACH ROW EXECUTE FUNCTION discovery.protect_run_terminal();
CREATE FUNCTION discovery.protect_execution_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.revision <= OLD.revision OR NEW.session_id <> OLD.session_id
       OR NEW.round_id <> OLD.round_id OR NEW.run_id <> OLD.run_id
       OR NEW.coordinator_boot <> OLD.coordinator_boot OR NEW.python_boot <> OLD.python_boot
       OR NEW.slot_no <> OLD.slot_no
       OR (OLD.physical_released_at IS NOT NULL AND
           (NEW.physical_released_at IS DISTINCT FROM OLD.physical_released_at OR NEW.state <> OLD.state)) THEN
        RAISE EXCEPTION 'STALE_EXECUTION_OR_BINDING' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER protect_execution_binding BEFORE UPDATE ON discovery.execution
    FOR EACH ROW EXECUTE FUNCTION discovery.protect_execution_binding();
CREATE FUNCTION discovery.check_slot_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM discovery.execution e LEFT JOIN discovery.execution_slot s
               ON s.slot_no=e.slot_no AND s.execution_id=e.id
               WHERE e.physical_released_at IS NULL AND s.execution_id IS NULL)
       OR EXISTS (SELECT 1 FROM discovery.execution_slot s JOIN discovery.execution e ON s.execution_id=e.id
                  WHERE e.physical_released_at IS NOT NULL) THEN
        RAISE EXCEPTION 'PHYSICAL_SLOT_BINDING_MISMATCH' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER execution_slot_consistency AFTER INSERT OR UPDATE OR DELETE ON discovery.execution
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION discovery.check_slot_binding();
CREATE CONSTRAINT TRIGGER slot_execution_consistency AFTER INSERT OR UPDATE OR DELETE ON discovery.execution_slot
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION discovery.check_slot_binding();

-- Explicit grants are deployment-owned. No role is created and PUBLIC receives no access.
REVOKE ALL ON ALL TABLES IN SCHEMA discovery FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA discovery FROM PUBLIC;
