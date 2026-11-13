package io.auralisy.session.adapters;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "session", schema = "discovery")
class SessionEntity {
    @Id
    @Column(length = 160)
    String id;

    @Column(name = "subject_id", nullable = false, length = 160)
    String subjectId;

    @Column(name = "input_revision", nullable = false)
    long inputRevision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_input", nullable = false, columnDefinition = "jsonb")
    String rawInput;

    @Version long revision;
    long epoch;

    @Column(length = 16)
    String status;

    @Column(name = "current_round_id", length = 160)
    String currentRoundId;

    @Column(name = "created_at")
    Instant createdAt;

    @Column(name = "last_user_action")
    Instant lastUserAction;

    @Column(name = "expires_at")
    Instant expiresAt;

    protected SessionEntity() {}
}
