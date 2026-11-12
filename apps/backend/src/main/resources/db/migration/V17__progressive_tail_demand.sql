ALTER TABLE discovery.publication_round
    ADD COLUMN progressive_remaining integer NOT NULL DEFAULT 0 CHECK (progressive_remaining >= 0),
    ADD COLUMN progressive_tail_claim varchar(160);
