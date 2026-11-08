-- Browser input is distinct from the resolved internal RawInput stored by the session owner.
ALTER TABLE discovery.session ADD COLUMN editable_input discovery.document;
ALTER TABLE discovery.subject ADD COLUMN auth_generation varchar(64);
CREATE INDEX subject_active_expiry ON discovery.subject(lifecycle,expires_at,id);

-- Minimal revocation receipt survives content purge. Replay these after restoring old data.
CREATE TABLE discovery.subject_revocation (
    credential_digest discovery.digest PRIMARY KEY,
    revoked_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL
);
REVOKE ALL ON discovery.subject_revocation FROM PUBLIC;
CREATE INDEX revocation_expiry ON discovery.subject_revocation(expires_at);
CREATE UNIQUE INDEX deletion_one_subject ON discovery.deletion_job(subject_id) WHERE session_id IS NULL;
CREATE UNIQUE INDEX deletion_one_session ON discovery.deletion_job(session_id) WHERE session_id IS NOT NULL;
ALTER TABLE discovery.execution ADD COLUMN cancellation_requested boolean NOT NULL DEFAULT false;

-- Publication-owned read projection; authoritative input, expiry and identity remain in session.
CREATE TABLE discovery.recovery_projection (
    session_id discovery.identifier PRIMARY KEY REFERENCES discovery.session(id) ON DELETE CASCADE,
    revision bigint NOT NULL CHECK (revision >= 0),
    state discovery.document NOT NULL
);
REVOKE ALL ON discovery.recovery_projection FROM PUBLIC;

CREATE FUNCTION discovery.protect_retired_editable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'STALE_SESSION_OR_BARRIER' USING ERRCODE='23514';
END $$;
CREATE TRIGGER protect_retired_editable BEFORE UPDATE ON discovery.session
    FOR EACH ROW WHEN (OLD.status <> 'ACTIVE' AND NEW.editable_input IS DISTINCT FROM OLD.editable_input)
    EXECUTE FUNCTION discovery.protect_retired_editable();
REVOKE ALL ON FUNCTION discovery.protect_retired_editable() FROM PUBLIC;
