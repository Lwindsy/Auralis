-- Derived only from admitted per-listener rows; not a neighbor/ranking graph.
CREATE TABLE statistics.album_popularity (
    dataset_id bigint NOT NULL, window_year integer NOT NULL, album_id integer NOT NULL,
    listeners bigint NOT NULL CHECK(listeners > 0),
    play_count numeric(30,0) NOT NULL CHECK(play_count >= 0),
    updated_min bigint NOT NULL, updated_max bigint NOT NULL CHECK(updated_max >= updated_min),
    PRIMARY KEY(dataset_id,window_year,album_id),
    FOREIGN KEY(dataset_id,album_id) REFERENCES catalog.album(dataset_id,id)
);
CREATE INDEX album_popularity_order ON statistics.album_popularity(dataset_id,window_year,listeners DESC,play_count DESC,album_id);
CREATE TRIGGER require_importing AFTER INSERT ON statistics.album_popularity
 REFERENCING NEW TABLE AS incoming_rows FOR EACH STATEMENT EXECUTE FUNCTION ingestion.require_importing_dataset();
CREATE VIEW statistics.current_album_popularity AS
 SELECT p.* FROM statistics.album_popularity p JOIN ingestion.active_dataset d ON d.dataset_id=p.dataset_id;
