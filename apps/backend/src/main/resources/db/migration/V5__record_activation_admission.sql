CREATE TABLE ingestion.activation_receipt (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    dataset_id bigint NOT NULL REFERENCES ingestion.dataset(id),
    admission_sha256 text NOT NULL CHECK(admission_sha256 ~ '^[0-9a-f]{64}$'),
    activated_at timestamptz NOT NULL DEFAULT now()
);
