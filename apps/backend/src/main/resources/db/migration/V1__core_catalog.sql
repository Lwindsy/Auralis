CREATE SCHEMA ingestion;
CREATE SCHEMA catalog;
CREATE SCHEMA statistics;
REVOKE ALL ON SCHEMA ingestion, catalog, statistics FROM PUBLIC;

CREATE TABLE ingestion.dataset (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    manifest_sha256 text NOT NULL UNIQUE CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$'),
    layout_sha256 text NOT NULL CHECK (layout_sha256 ~ '^[0-9a-f]{64}$'),
    purpose text NOT NULL CHECK (purpose = 'private-development'),
    manifest jsonb NOT NULL CHECK (jsonb_typeof(manifest) = 'object'),
    status text NOT NULL CHECK (status IN ('IMPORTING','VALIDATED','ACTIVE','RETIRED','REJECTED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    activated_at timestamptz
);
CREATE TABLE ingestion.active_dataset (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    dataset_id bigint NOT NULL REFERENCES ingestion.dataset(id)
);
CREATE TABLE ingestion.file_progress (
    dataset_id bigint NOT NULL REFERENCES ingestion.dataset(id),
    file_key text NOT NULL,
    committed_rows bigint NOT NULL DEFAULT 0 CHECK (committed_rows >= 0),
    complete boolean NOT NULL DEFAULT false,
    PRIMARY KEY (dataset_id,file_key)
);
CREATE TABLE ingestion.import_chunk (
    dataset_id bigint NOT NULL REFERENCES ingestion.dataset(id), file_key text NOT NULL,
    start_row bigint NOT NULL, row_count integer NOT NULL CHECK(row_count > 0),
    sha256 text NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY(dataset_id,file_key,start_row)
);

CREATE TABLE catalog.genre (
    dataset_id bigint NOT NULL REFERENCES ingestion.dataset(id),
    id integer NOT NULL, mbid uuid NOT NULL, name text NOT NULL, comment text NOT NULL,
    PRIMARY KEY(dataset_id,id), UNIQUE(dataset_id,mbid)
);
CREATE TABLE catalog.genre_alias (
    dataset_id bigint NOT NULL, source_id integer NOT NULL, genre_id integer NOT NULL,
    name text NOT NULL, sort_name text NOT NULL, locale text, source_type integer,
    primary_for_locale boolean NOT NULL,
    PRIMARY KEY(dataset_id,source_id),
    FOREIGN KEY(dataset_id,genre_id) REFERENCES catalog.genre(dataset_id,id)
);
CREATE TABLE catalog.genre_relation (
    dataset_id bigint NOT NULL, source_id integer NOT NULL,
    source_genre integer NOT NULL, target_genre integer NOT NULL,
    relation_type text NOT NULL CHECK(relation_type IN ('subgenre','influenced_by','fusion_of')),
    source_updated timestamptz NOT NULL,
    PRIMARY KEY(dataset_id,source_id),
    FOREIGN KEY(dataset_id,source_genre) REFERENCES catalog.genre(dataset_id,id),
    FOREIGN KEY(dataset_id,target_genre) REFERENCES catalog.genre(dataset_id,id),
    CHECK(relation_type <> 'subgenre' OR source_genre <> target_genre)
);
CREATE TABLE catalog.artist (
    dataset_id bigint NOT NULL REFERENCES ingestion.dataset(id),
    id integer NOT NULL, mbid uuid NOT NULL, name text NOT NULL, sort_name text NOT NULL,
    comment text NOT NULL, source_type integer, source_updated timestamptz NOT NULL,
    PRIMARY KEY(dataset_id,id), UNIQUE(dataset_id,mbid)
);
CREATE TABLE catalog.album (
    dataset_id bigint NOT NULL REFERENCES ingestion.dataset(id),
    id integer NOT NULL, mbid uuid NOT NULL, title text NOT NULL, comment text NOT NULL,
    source_credit_id integer NOT NULL, primary_type text,
    first_year integer, first_month smallint CHECK(first_month BETWEEN 1 AND 12),
    first_day smallint CHECK(first_day BETWEEN 1 AND 31), source_updated timestamptz NOT NULL,
    PRIMARY KEY(dataset_id,id), UNIQUE(dataset_id,mbid)
);
CREATE TABLE catalog.album_secondary_type (
    dataset_id bigint NOT NULL, album_id integer NOT NULL, name text NOT NULL,
    PRIMARY KEY(dataset_id,album_id,name),
    FOREIGN KEY(dataset_id,album_id) REFERENCES catalog.album(dataset_id,id)
);
CREATE TABLE catalog.album_credit (
    dataset_id bigint NOT NULL, album_id integer NOT NULL, position smallint NOT NULL CHECK(position >= 0),
    artist_id integer NOT NULL, credited_name text NOT NULL, join_phrase text NOT NULL,
    PRIMARY KEY(dataset_id,album_id,position),
    FOREIGN KEY(dataset_id,album_id) REFERENCES catalog.album(dataset_id,id),
    FOREIGN KEY(dataset_id,artist_id) REFERENCES catalog.artist(dataset_id,id)
);
CREATE INDEX album_credit_artist ON catalog.album_credit(dataset_id,artist_id,album_id);
CREATE TABLE catalog.album_genre (
    dataset_id bigint NOT NULL, album_id integer NOT NULL, genre_id integer NOT NULL,
    source_tag_id integer NOT NULL, support_count integer NOT NULL CHECK(support_count > 0),
    source_updated timestamptz NOT NULL,
    PRIMARY KEY(dataset_id,album_id,genre_id,source_tag_id),
    FOREIGN KEY(dataset_id,album_id) REFERENCES catalog.album(dataset_id,id),
    FOREIGN KEY(dataset_id,genre_id) REFERENCES catalog.genre(dataset_id,id)
);
CREATE INDEX album_genre_reverse ON catalog.album_genre(dataset_id,genre_id,album_id);
CREATE TABLE catalog.release_map (
    dataset_id bigint NOT NULL, id integer NOT NULL, mbid uuid NOT NULL, album_id integer NOT NULL,
    PRIMARY KEY(dataset_id,id), UNIQUE(dataset_id,mbid),
    FOREIGN KEY(dataset_id,album_id) REFERENCES catalog.album(dataset_id,id)
);
CREATE TABLE catalog.album_redirect (
    dataset_id bigint NOT NULL, old_mbid uuid NOT NULL, album_id integer NOT NULL,
    PRIMARY KEY(dataset_id,old_mbid),
    FOREIGN KEY(dataset_id,album_id) REFERENCES catalog.album(dataset_id,id)
);
CREATE TABLE catalog.artist_redirect (
    dataset_id bigint NOT NULL, old_mbid uuid NOT NULL, artist_id integer NOT NULL,
    PRIMARY KEY(dataset_id,old_mbid),
    FOREIGN KEY(dataset_id,artist_id) REFERENCES catalog.artist(dataset_id,id)
);
CREATE TABLE catalog.release_redirect (
    dataset_id bigint NOT NULL, old_mbid uuid NOT NULL, release_id integer NOT NULL,
    PRIMARY KEY(dataset_id,old_mbid),
    FOREIGN KEY(dataset_id,release_id) REFERENCES catalog.release_map(dataset_id,id)
);
CREATE TABLE catalog.album_alias (
    dataset_id bigint NOT NULL, source_id integer NOT NULL, album_id integer NOT NULL,
    name text NOT NULL, sort_name text NOT NULL, locale text, source_type integer,
    primary_for_locale boolean NOT NULL, PRIMARY KEY(dataset_id,source_id),
    FOREIGN KEY(dataset_id,album_id) REFERENCES catalog.album(dataset_id,id)
);
CREATE TABLE catalog.artist_alias (
    dataset_id bigint NOT NULL, source_id integer NOT NULL, artist_id integer NOT NULL,
    name text NOT NULL, sort_name text NOT NULL, locale text, source_type integer,
    primary_for_locale boolean NOT NULL, PRIMARY KEY(dataset_id,source_id),
    FOREIGN KEY(dataset_id,artist_id) REFERENCES catalog.artist(dataset_id,id)
);
CREATE INDEX album_name ON catalog.album(dataset_id,lower(title) text_pattern_ops);
CREATE INDEX artist_name ON catalog.artist(dataset_id,lower(name) text_pattern_ops);
CREATE INDEX album_alias_name ON catalog.album_alias(dataset_id,lower(name) text_pattern_ops);
CREATE INDEX artist_alias_name ON catalog.artist_alias(dataset_id,lower(name) text_pattern_ops);

CREATE TABLE statistics.observation (
    dataset_id bigint NOT NULL REFERENCES ingestion.dataset(id), id bigint NOT NULL,
    entity_kind text NOT NULL CHECK(entity_kind IN ('album','artist')),
    window_year integer NOT NULL CHECK(window_year = 0 OR window_year BETWEEN 1900 AND 9998),
    listener_id bigint NOT NULL CHECK(listener_id > 0),
    source_count bigint NOT NULL, list_length integer NOT NULL CHECK(list_length >= 0),
    from_ts bigint NOT NULL, to_ts bigint NOT NULL, last_updated bigint NOT NULL,
    source_member text NOT NULL, source_line bigint NOT NULL CHECK(source_line > 0),
    row_sha256 text NOT NULL CHECK(row_sha256 ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY(dataset_id,id), UNIQUE(dataset_id,id,entity_kind),
    UNIQUE(dataset_id,entity_kind,window_year,listener_id),
    UNIQUE(dataset_id,source_member,source_line),
    CHECK(source_count >= list_length), CHECK(from_ts < to_ts),
    CHECK(window_year = 0 OR
        (extract(year FROM to_timestamp(from_ts) AT TIME ZONE 'UTC') = window_year AND
         extract(year FROM to_timestamp(to_ts) AT TIME ZONE 'UTC') = window_year + 1))
);
CREATE TABLE statistics.album_listen (
    dataset_id bigint NOT NULL, observation_id bigint NOT NULL, album_id integer NOT NULL,
    listen_count bigint NOT NULL CHECK(listen_count >= 0), source_items integer NOT NULL CHECK(source_items > 0),
    entity_kind text GENERATED ALWAYS AS ('album'::text) STORED,
    PRIMARY KEY(dataset_id,observation_id,album_id),
    FOREIGN KEY(dataset_id,observation_id,entity_kind) REFERENCES statistics.observation(dataset_id,id,entity_kind),
    FOREIGN KEY(dataset_id,album_id) REFERENCES catalog.album(dataset_id,id)
);
CREATE INDEX album_listen_reverse ON statistics.album_listen(dataset_id,album_id,observation_id);
CREATE TABLE statistics.artist_listen (
    dataset_id bigint NOT NULL, observation_id bigint NOT NULL, artist_id integer NOT NULL,
    listen_count bigint NOT NULL CHECK(listen_count >= 0), source_items integer NOT NULL CHECK(source_items > 0),
    entity_kind text GENERATED ALWAYS AS ('artist'::text) STORED,
    PRIMARY KEY(dataset_id,observation_id,artist_id),
    FOREIGN KEY(dataset_id,observation_id,entity_kind) REFERENCES statistics.observation(dataset_id,id,entity_kind),
    FOREIGN KEY(dataset_id,artist_id) REFERENCES catalog.artist(dataset_id,id)
);
CREATE INDEX artist_listen_reverse ON statistics.artist_listen(dataset_id,artist_id,observation_id);

-- Logical identity is MBID. Integer keys are scoped to one immutable dataset.
-- Readers see one validated dataset. Staged/retired data is not exposed by these views.
CREATE VIEW catalog.current_album AS SELECT a.* FROM catalog.album a JOIN ingestion.active_dataset d ON d.dataset_id=a.dataset_id;
CREATE VIEW catalog.current_artist AS SELECT a.* FROM catalog.artist a JOIN ingestion.active_dataset d ON d.dataset_id=a.dataset_id;
CREATE VIEW catalog.current_genre AS SELECT a.* FROM catalog.genre a JOIN ingestion.active_dataset d ON d.dataset_id=a.dataset_id;
CREATE VIEW statistics.current_album_listen AS
 SELECT s.dataset_id,s.album_id,o.window_year,o.listener_id,s.listen_count,s.source_items,o.from_ts,o.to_ts,o.last_updated
 FROM statistics.album_listen s JOIN statistics.observation o ON (o.dataset_id,o.id)=(s.dataset_id,s.observation_id)
 JOIN ingestion.active_dataset d ON d.dataset_id=s.dataset_id;
CREATE VIEW statistics.current_artist_listen AS
 SELECT s.dataset_id,s.artist_id,o.window_year,o.listener_id,s.listen_count,s.source_items,o.from_ts,o.to_ts,o.last_updated
 FROM statistics.artist_listen s JOIN statistics.observation o ON (o.dataset_id,o.id)=(s.dataset_id,s.observation_id)
 JOIN ingestion.active_dataset d ON d.dataset_id=s.dataset_id;

DO $$ DECLARE t text; BEGIN
 FOREACH t IN ARRAY ARRAY['genre_alias','genre_relation','album_secondary_type','album_credit','album_genre','release_map','album_redirect','artist_redirect','release_redirect','album_alias','artist_alias'] LOOP
  EXECUTE format('CREATE VIEW catalog.current_%I AS SELECT a.* FROM catalog.%I a JOIN ingestion.active_dataset d ON d.dataset_id=a.dataset_id',t,t);
 END LOOP;
END $$;
