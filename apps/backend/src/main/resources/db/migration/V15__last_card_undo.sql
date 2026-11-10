CREATE TABLE discovery.card_undo (
    session_id discovery.identifier PRIMARY KEY REFERENCES discovery.session(id) ON DELETE CASCADE,
    slot_id discovery.identifier NOT NULL,
    previous_round_id discovery.identifier NOT NULL,
    previous_candidate_id discovery.identifier NOT NULL,
    operation varchar(16) NOT NULL CHECK(operation IN ('HEARD','REPLACE')),
    heard_added boolean NOT NULL,
    FOREIGN KEY(session_id,previous_round_id,previous_candidate_id)
        REFERENCES discovery.recommendation_card(session_id,round_id,candidate_id) ON DELETE CASCADE
);
REVOKE ALL ON discovery.card_undo FROM PUBLIC;
