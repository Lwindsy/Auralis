-- A COPY statement checks its batch once, rather than performing a query per row.
CREATE FUNCTION ingestion.require_importing_dataset() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM (SELECT DISTINCT dataset_id FROM incoming_rows) n
        JOIN ingestion.dataset d ON d.id=n.dataset_id WHERE d.status <> 'IMPORTING'
    ) THEN RAISE EXCEPTION 'DATASET_IMMUTABLE' USING ERRCODE='55000'; END IF;
    RETURN NULL;
END $$;
DO $$ DECLARE r record; BEGIN
 FOR r IN SELECT schemaname,tablename FROM pg_tables WHERE schemaname IN ('catalog','statistics') LOOP
  EXECUTE format('CREATE TRIGGER require_importing AFTER INSERT ON %I.%I REFERENCING NEW TABLE AS incoming_rows FOR EACH STATEMENT EXECUTE FUNCTION ingestion.require_importing_dataset()',r.schemaname,r.tablename);
 END LOOP;
END $$;
