package com.mulgil.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.sql.Timestamp;
import java.util.UUID;

@Repository
class UserRepository {
    private final JdbcClient jdbc;
    private final Clock clock;

    UserRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    User upsertGoogle(GoogleIdentity identity) {
        UUID candidateId = UUID.randomUUID();
        return jdbc.sql("""
                        INSERT INTO users (id, provider, provider_subject, email, display_name, created_at)
                        VALUES (:id, 'google', :subject, :email, :displayName, :createdAt)
                        ON CONFLICT (provider, provider_subject) DO UPDATE
                        SET email = EXCLUDED.email, display_name = EXCLUDED.display_name
                        RETURNING id, email, display_name
                        """)
                .param("id", candidateId)
                .param("subject", identity.subject())
                .param("email", identity.email())
                .param("displayName", identity.displayName())
                .param("createdAt", Timestamp.from(clock.instant()))
                .query((row, rowNumber) -> new User(
                        row.getObject("id", UUID.class),
                        row.getString("email"),
                        row.getString("display_name")))
                .single();
    }
}
