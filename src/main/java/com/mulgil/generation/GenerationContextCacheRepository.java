package com.mulgil.generation;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface GenerationContextCacheRepository {
    Reservation reserve(Key key, Instant now, Instant expiresAt);
    boolean activate(UUID id, int generation, long tokenCount, Instant now, Instant expiresAt);
    boolean invalidate(UUID id, int generation, String errorCode, Instant now);
    void touch(UUID id, Instant now);
    boolean fail(UUID id, int generation, String errorCode, Instant now);

    record Key(UUID ownerId, String snapshotHash, String provider, String model, String location,
               String inputContract) {}
    record Entry(UUID id, Key key, String status, Long tokenCount, Instant expiresAt, int generation) {}
    record Reservation(Entry entry, boolean creator, boolean replacesExpired) {}
}

@Repository
class JdbcGenerationContextCacheRepository implements GenerationContextCacheRepository {
    private final JdbcClient jdbc;

    JdbcGenerationContextCacheRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Reservation reserve(Key key, Instant now, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        Optional<Entry> claimed = jdbc.sql("""
                INSERT INTO generation_context_caches
                    (id,owner_id,snapshot_hash,provider,model_id,location,input_contract,status,
                     generation,expires_at,created_at,updated_at)
                VALUES (:id,:owner,:snapshot,:provider,:model,:location,:contract,'creating',1,:expires,:now,:now)
                ON CONFLICT (owner_id,snapshot_hash,provider,model_id,location,input_contract)
                DO UPDATE SET status='creating',token_count=NULL,error_code=NULL,
                    generation=generation_context_caches.generation+1,expires_at=:expires,updated_at=:now
                WHERE generation_context_caches.expires_at<=:now
                   OR generation_context_caches.status='failed'
                RETURNING id,owner_id,snapshot_hash,provider,model_id,location,input_contract,
                          status,token_count,expires_at,generation
                """).param("id", id).param("owner", key.ownerId()).param("snapshot", key.snapshotHash())
                .param("provider", key.provider()).param("model", key.model()).param("location", key.location())
                .param("contract", key.inputContract()).param("expires", Timestamp.from(expiresAt))
                .param("now", Timestamp.from(now)).query(this::entry).optional();
        if (claimed.isPresent()) {
            Entry entry = claimed.orElseThrow();
            return new Reservation(entry, true, entry.generation() > 1);
        }
        Entry current = jdbc.sql("""
                SELECT id,owner_id,snapshot_hash,provider,model_id,location,input_contract,
                       status,token_count,expires_at,generation
                FROM generation_context_caches
                WHERE owner_id=:owner AND snapshot_hash=:snapshot AND provider=:provider
                  AND model_id=:model AND location=:location AND input_contract=:contract
                """).param("owner", key.ownerId()).param("snapshot", key.snapshotHash())
                .param("provider", key.provider()).param("model", key.model()).param("location", key.location())
                .param("contract", key.inputContract()).query(this::entry).single();
        return new Reservation(current, false, false);
    }

    @Override
    public boolean activate(UUID id, int generation, long tokenCount, Instant now, Instant expiresAt) {
        return jdbc.sql("""
                UPDATE generation_context_caches SET status='active',token_count=:tokens,error_code=NULL,
                    expires_at=:expires,last_used_at=:now,updated_at=:now
                WHERE id=:id AND generation=:generation AND status='creating'
                """).param("tokens", tokenCount).param("expires", Timestamp.from(expiresAt))
                .param("now", Timestamp.from(now)).param("id", id).param("generation", generation).update() == 1;
    }

    @Override
    public boolean invalidate(UUID id, int generation, String errorCode, Instant now) {
        return jdbc.sql("""
                UPDATE generation_context_caches SET status='failed',error_code=:error,updated_at=:now
                WHERE id=:id AND generation=:generation AND status='active'
                """).param("error", errorCode).param("now", Timestamp.from(now)).param("id", id)
                .param("generation", generation).update() == 1;
    }

    @Override
    public void touch(UUID id, Instant now) {
        jdbc.sql("UPDATE generation_context_caches SET last_used_at=:now,updated_at=:now WHERE id=:id")
                .param("now", Timestamp.from(now)).param("id", id).update();
    }

    @Override
    public boolean fail(UUID id, int generation, String errorCode, Instant now) {
        return jdbc.sql("""
                UPDATE generation_context_caches SET status='failed',error_code=:error,updated_at=:now
                WHERE id=:id AND generation=:generation AND status='creating'
                """).param("error", errorCode).param("now", Timestamp.from(now)).param("id", id)
                .param("generation", generation).update() == 1;
    }

    private Entry entry(java.sql.ResultSet row, int ignored) throws java.sql.SQLException {
        Key key = new Key(row.getObject("owner_id", UUID.class), row.getString("snapshot_hash"),
                row.getString("provider"), row.getString("model_id"), row.getString("location"),
                row.getString("input_contract"));
        return new Entry(row.getObject("id", UUID.class), key, row.getString("status"),
                row.getObject("token_count", Long.class), row.getTimestamp("expires_at").toInstant(),
                row.getInt("generation"));
    }
}
