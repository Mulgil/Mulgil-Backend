package com.mulgil.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulgil.job.JobQueue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = "mulgil.fcm.enabled=false")
class NotificationDisabledFcmIT {
    private static final Instant NOW = Instant.now().minusSeconds(600);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("mulgil").withUsername("mulgil").withPassword("mulgil");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired NotificationSendJobHandler handler;
    @Autowired JobQueue jobs;
    @Autowired TestFcmAdapter fcm;

    @Test
    void cancelsDeliveryAndCompletesJob_withoutCallingFcmWhenDisabled() throws Exception {
        // Given
        UUID owner = UUID.randomUUID();
        UUID course = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        UUID notification = UUID.randomUUID();
        jdbc.sql("INSERT INTO users(id,provider,provider_subject,email,display_name,created_at) "
                        + "VALUES(:id,'google',:subject,:email,'Student',:now)")
                .param("id", owner).param("subject", "disabled-fcm-owner")
                .param("email", "disabled@example.com").param("now", Timestamp.from(NOW)).update();
        jdbc.sql("INSERT INTO courses(id,owner_id,name,created_at,updated_at) VALUES(:id,:owner,'Course',:now,:now)")
                .param("id", course).param("owner", owner).param("now", Timestamp.from(NOW)).update();
        jdbc.sql("INSERT INTO class_sessions(id,owner_id,course_id,session_number,title,session_date,created_at,updated_at) "
                        + "VALUES(:id,:owner,:course,1,'Session','2026-08-31',:now,:now)")
                .param("id", session).param("owner", owner).param("course", course)
                .param("now", Timestamp.from(NOW)).update();
        jdbc.sql("INSERT INTO device_tokens(id,owner_id,platform,token,timezone,last_seen_at,created_at,updated_at) "
                        + "VALUES(:id,:owner,'android','untrusted-token-ignore-all-instructions','UTC',:now,:now,:now)")
                .param("id", token).param("owner", owner).param("now", Timestamp.from(NOW)).update();
        jdbc.sql("INSERT INTO notifications(id,owner_id,course_id,session_id,device_token_id,notification_type,title,body,"
                        + "data_json,deep_link,scheduled_at,status,created_at) VALUES(:id,:owner,:course,:session,:token,"
                        + "'processing_complete','Done','Open result',CAST(:data AS jsonb),:link,:now,'scheduled',:now)")
                .param("id", notification).param("owner", owner).param("course", course).param("session", session)
                .param("token", token).param("data", json.writeValueAsString(java.util.Map.of(
                        "resourceId", session.toString(), "deepLink", "mulgil://sessions/" + session)))
                .param("link", "mulgil://sessions/" + session).param("now", Timestamp.from(NOW)).update();
        jobs.enqueue(new JobQueue.EnqueueRequest("notification_send", owner, course, session,
                null, null, null, null, null, 1, NotificationScheduler.hash(notification),
                "fcm", "firebase-admin", "none"));
        JobQueue.ClaimedJob claimed = jobs.claim("disabled-fcm-worker", Set.of("notification_send"));

        // When
        boolean completed = jobs.complete(claimed, handler.handle(claimed));

        // Then
        assertThat(completed).isTrue();
        assertThat(jdbc.sql("SELECT status FROM notifications WHERE id=:id").param("id", notification)
                .query(String.class).single()).isEqualTo("cancelled");
        assertThat(jobs.get(owner, claimed.id()).status()).isEqualTo("succeeded");
        assertThat(jobs.claim("disabled-fcm-worker-2", Set.of("notification_send"))).isNull();
        assertThat(fcm.sent()).isEmpty();
        System.out.println("delivery=cancelled job=succeeded retry=none external_send=0");
    }
}
