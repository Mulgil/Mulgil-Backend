package com.mulgil.notification;

import com.mulgil.common.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
final class NotificationService {
    private final JdbcClient jdbc;
    private final Clock clock;

    NotificationService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    DeviceToken register(UUID ownerId, String token, String platform, String timezone) {
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException exception) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "Invalid timezone.");
        }
        Instant now = clock.instant();
        return jdbc.sql("""
                        INSERT INTO device_tokens
                            (id,owner_id,platform,token,timezone,last_seen_at,created_at,updated_at)
                        VALUES (:id,:owner,:platform,:token,:timezone,:now,:now,:now)
                        ON CONFLICT (token) DO UPDATE SET platform=EXCLUDED.platform,
                            timezone=EXCLUDED.timezone,last_seen_at=EXCLUDED.last_seen_at,
                            updated_at=EXCLUDED.updated_at
                        WHERE device_tokens.owner_id=EXCLUDED.owner_id
                        RETURNING id,platform,timezone,last_seen_at,created_at,updated_at
                        """).param("id", UUID.randomUUID()).param("owner", ownerId).param("platform", platform)
                .param("token", token).param("timezone", timezone).param("now", Timestamp.from(now))
                .query((row, ignored) -> device(row)).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "DEVICE_TOKEN_OWNED",
                        "Device token belongs to another account."));
    }

    void remove(UUID ownerId, String token) {
        jdbc.sql("DELETE FROM device_tokens WHERE owner_id=:owner AND token=:token")
                .param("owner", ownerId).param("token", token).update();
    }

    List<NotificationView> list(UUID ownerId, boolean unreadOnly) {
        return jdbc.sql("""
                        SELECT id,notification_type,title,body,deep_link,status,scheduled_at,sent_at
                        FROM notifications
                        WHERE owner_id=:owner AND (:unread=false OR read_at IS NULL)
                        ORDER BY created_at DESC,id DESC
                        """).param("owner", ownerId).param("unread", unreadOnly)
                .query((row, ignored) -> notification(row)).list();
    }

    private static DeviceToken device(ResultSet row) throws SQLException {
        return new DeviceToken(row.getObject("id", UUID.class), row.getString("platform"),
                row.getString("timezone"), row.getTimestamp("last_seen_at").toInstant(),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    }

    private static NotificationView notification(ResultSet row) throws SQLException {
        Timestamp sent = row.getTimestamp("sent_at");
        return new NotificationView(row.getObject("id", UUID.class), row.getString("notification_type"),
                row.getString("title"), row.getString("body"), row.getString("deep_link"),
                row.getString("status"), row.getTimestamp("scheduled_at").toInstant(),
                sent == null ? null : sent.toInstant());
    }

    record DeviceToken(UUID id, String platform, String timezone, Instant lastSeenAt,
                       Instant createdAt, Instant updatedAt) {}
    record NotificationView(UUID id, String type, String title, String body, String deepLink,
                            String status, Instant scheduledAt, Instant sentAt) {}
}
