-- Budget owns persistent authorization policy and financial-only recovery data.
ALTER TABLE discovery.budget_scope ADD COLUMN policy jsonb;
CREATE UNIQUE INDEX budget_one_session_root ON discovery.budget_scope(session_id)
    WHERE parent_scope_id IS NULL AND policy IS NOT NULL;
CREATE UNIQUE INDEX budget_one_round ON discovery.budget_scope((policy->>'round'))
    WHERE policy ? 'round';
ALTER TABLE discovery.invocation
    ADD COLUMN execution_digest discovery.digest,
    ADD COLUMN run_digest discovery.digest,
    ADD COLUMN cost_class varchar(16),
    ADD COLUMN model_ref discovery.identifier,
    ADD COLUMN grant_result jsonb,
    ADD COLUMN settled_microusd bigint CHECK (settled_microusd >= 0),
    ADD COLUMN input_tokens bigint CHECK (input_tokens >= 0),
    ADD COLUMN cached_input_tokens bigint CHECK (cached_input_tokens >= 0),
    ADD COLUMN output_tokens bigint CHECK (output_tokens >= 0);
ALTER TABLE discovery.usage_receipt ADD COLUMN response jsonb;
CREATE INDEX invocation_budget_lane ON discovery.invocation(scope_id,lane);
