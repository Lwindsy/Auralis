-- Completed jobs are already removed by the application. Preserve every pending job and retry time.
DELETE FROM discovery.deletion_job WHERE state = 'COMPLETED';
DROP INDEX discovery.deletion_pending;
ALTER TABLE discovery.deletion_job DROP COLUMN state, DROP COLUMN step, DROP COLUMN cursor;
CREATE INDEX deletion_pending ON discovery.deletion_job(next_attempt_at,id);
