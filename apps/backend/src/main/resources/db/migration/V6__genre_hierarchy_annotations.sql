CREATE TABLE ingestion.genre_hierarchy_batch (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    dataset_id bigint NOT NULL REFERENCES ingestion.dataset(id),
    annotation_sha256 text NOT NULL UNIQUE CHECK (annotation_sha256 ~ '^[0-9a-f]{64}$'),
    source jsonb NOT NULL,
    purpose text NOT NULL CHECK (purpose = 'private-development'),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, dataset_id)
);
CREATE TABLE catalog.genre_hierarchy_annotation (
    batch_id bigint NOT NULL,
    dataset_id bigint NOT NULL,
    genre_mbid uuid NOT NULL,
    status text NOT NULL CHECK (status IN ('classified','uncategorised','ambiguous','no_strict_match','other_rym_section_only')),
    annotation jsonb NOT NULL,
    PRIMARY KEY (batch_id, genre_mbid),
    FOREIGN KEY (batch_id, dataset_id) REFERENCES ingestion.genre_hierarchy_batch(id, dataset_id) ON DELETE CASCADE,
    FOREIGN KEY (dataset_id, genre_mbid) REFERENCES catalog.genre(dataset_id, mbid)
);
CREATE VIEW catalog.current_genre_hierarchy_annotation AS
SELECT a.* FROM catalog.genre_hierarchy_annotation a
JOIN ingestion.active_dataset d ON d.dataset_id = a.dataset_id
WHERE a.batch_id = (SELECT max(b.id) FROM ingestion.genre_hierarchy_batch b WHERE b.dataset_id = d.dataset_id);
