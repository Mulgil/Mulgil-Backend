package com.mulgil.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

@Repository
class RefreshTokenStore {
    private final JdbcClient jdbc;
    private final Clock clock;

    RefreshTokenStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    void create(UUID userId, UUID familyId, String tokenHash, Instant expiresAt) {
        jdbc.sql("""
                        INSERT INTO auth_refresh_tokens
                            (id, user_id, token_hash, family_id, expires_at, created_at)
                        VALUES (:id, :userId, :tokenHash, :familyId, :expiresAt, :createdAt)
                        """)
                .param("id", UUID.randomUUID())
                .param("userId", userId)
                .param("tokenHash", tokenHash)
                .param("familyId", familyId)
                .param("expiresAt", Timestamp.from(expiresAt))
                .param("createdAt", Timestamp.from(clock.instant()))
                .update();
    }

    @Transactional
    Rotation rotate(String presentedHash, String successorHash, Instant successorExpiresAt) {
        Optional<StoredRefresh> stored = jdbc.sql("""
                        SELECT t.id, t.user_id, t.family_id, t.expires_at, t.revoked_at,
                               u.email, u.display_name
                        FROM auth_refresh_tokens t
                        JOIN users u ON u.id = t.user_id
                        WHERE t.token_hash = :tokenHash
                        FOR UPDATE OF t
                        """)
                .param("tokenHash", presentedHash)
                .query((row, rowNumber) -> new StoredRefresh(
                        row.getObject("id", UUID.class),
                        row.getObject("user_id", UUID.class),
                        row.getObject("family_id", UUID.class),
                        row.getTimestamp("expires_at").toInstant(),
                        row.getTimestamp("revoked_at") == null ? null : row.getTimestamp("revoked_at").toInstant(),
                        row.getString("email"),
                        row.getString("display_name")))
                .optional();

        if (stored.isEmpty()) {
            return Rotation.invalid();
        }
        StoredRefresh token = stored.get();
        Instant now = clock.instant();
        if (token.revokedAt() != null) {
            revokeFamily(token.familyId(), now);
            return Rotation.reused();
        }
        if (!token.expiresAt().isAfter(now)) {
            return Rotation.invalid();
        }

        jdbc.sql("UPDATE auth_refresh_tokens SET revoked_at = :now WHERE id = :id")
                .param("now", Timestamp.from(now))
                .param("id", token.id())
                .update();
        create(token.userId(), token.familyId(), successorHash, successorExpiresAt);
        return Rotation.success(new User(token.userId(), token.email(), token.displayName()));
    }

    @Transactional
    boolean revokePresentedFamily(String presentedHash) {
        Optional<UUID> familyId = jdbc.sql("""
                        SELECT family_id FROM auth_refresh_tokens
                        WHERE token_hash = :tokenHash
                        FOR UPDATE
                        """)
                .param("tokenHash", presentedHash)
                .query(UUID.class)
                .optional();
        familyId.ifPresent(id -> revokeFamily(id, clock.instant()));
        return familyId.isPresent();
    }

    private void revokeFamily(UUID familyId, Instant revokedAt) {
        jdbc.sql("""
                        UPDATE auth_refresh_tokens SET revoked_at = :revokedAt
                        WHERE family_id = :familyId AND revoked_at IS NULL
                        """)
                .param("revokedAt", Timestamp.from(revokedAt))
                .param("familyId", familyId)
                .update();
    }

    private record StoredRefresh(
            UUID id,
            UUID userId,
            UUID familyId,
            Instant expiresAt,
            Instant revokedAt,
            String email,
            String displayName
    ) {}

    record Rotation(Status status, User user) {
        static Rotation success(User user) {
            return new Rotation(Status.SUCCESS, user);
        }

        static Rotation invalid() {
            return new Rotation(Status.INVALID, null);
        }

        static Rotation reused() {
            return new Rotation(Status.REUSED, null);
        }
    }

    enum Status { SUCCESS, INVALID, REUSED }
}
