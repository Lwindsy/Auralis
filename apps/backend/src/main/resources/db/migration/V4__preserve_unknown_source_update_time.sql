-- MB last_updated is nullable. Snapshot provenance remains mandatory; do not invent a row timestamp.
ALTER TABLE catalog.artist ALTER COLUMN source_updated DROP NOT NULL;
ALTER TABLE catalog.album ALTER COLUMN source_updated DROP NOT NULL;
ALTER TABLE catalog.album_genre ALTER COLUMN source_updated DROP NOT NULL;
ALTER TABLE catalog.genre_relation ALTER COLUMN source_updated DROP NOT NULL;
