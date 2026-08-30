package com.mulgil.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class FlywaySchemaIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("mulgil")
            .withUsername("mulgil")
            .withPassword("mulgil");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    JdbcClient jdbc;

    SchemaFixture fixture;

    @BeforeEach
    void createOwnedDomain() {
        fixture = new SchemaFixture(jdbc);
    }

    @Test
    void appliesV001ThroughV012_whenDatabaseIsFresh() {
        List<String> versions = jdbc.sql("SELECT version FROM flyway_schema_history ORDER BY installed_rank")
                .query(String.class).list();
        Integer requiredTables = jdbc.sql("""
                        SELECT count(*) FROM information_schema.tables
                        WHERE table_schema = 'public' AND table_name IN (
                            'annotation_documents', 'ink_strokes', 'emphasis_regions', 'handwriting_blocks',
                            'document_pages', 'content_blocks', 'transcript_segments', 'chunks', 'summaries',
                            'mindmaps', 'quiz_questions', 'quiz_attempts', 'progress_status', 'ai_jobs',
                            'device_tokens', 'notifications', 'speech_input_cleanups', 'ai_provider_usage')
                        """).query(Integer.class).single();
        List<String> requiredIndexes = jdbc.sql("""
                        SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND indexname IN (
                            'document_pages_material_page_uidx', 'document_pages_exam_session_page_uidx',
                            'chunks_embedding_hnsw_idx', 'ai_jobs_queued_claim_idx',
                            'ai_jobs_expired_running_idx', 'notifications_scheduled_idx',
                            'speech_input_cleanups_due_idx', 'speech_input_cleanups_owner_idx',
                            'ai_jobs_active_cache_fingerprint_uidx', 'ai_provider_usage_job_idx') ORDER BY indexname
                        """).query(String.class).list();
        Integer jobColumns = jdbc.sql("""
                        SELECT count(*) FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = 'ai_jobs' AND (
                            (column_name IN ('exam_resource_id', 'source_hash') AND is_nullable = 'YES')
                            OR (column_name = 'input_version' AND data_type = 'integer')
                            OR column_name IN ('claimed_by', 'last_heartbeat_at', 'lease_expires_at',
                                               'cache_fingerprint'))
                        """).query(Integer.class).single();
        Integer nullableSourceParents = jdbc.sql("""
                        SELECT count(*) FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name IN ('document_pages', 'content_blocks')
                          AND column_name IN ('material_id', 'exam_resource_id')
                          AND is_nullable = 'YES'
                        """).query(Integer.class).single();
        Integer requiredConstraints = jdbc.sql("""
                        SELECT count(*) FROM pg_constraint WHERE conname IN (
                            'exam_session_members_pkey', 'document_pages_exactly_one_parent',
                            'content_blocks_exactly_one_source', 'chunks_exactly_one_source',
                            'ai_jobs_source_parent_check', 'ai_jobs_idempotency_key_key',
                            'quiz_attempts_exactly_one_scope', 'progress_status_exactly_one_scope',
                            'quiz_attempts_session_question_fkey', 'quiz_attempts_exam_question_fkey',
                            'progress_status_session_question_fkey', 'progress_status_exam_question_fkey')
                        """).query(Integer.class).single();
        Integer requiredTriggers = jdbc.sql("""
                        SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal AND tgname IN (
                            'document_pages_exam_session_check', 'content_blocks_source_check',
                            'content_blocks_exam_session_check', 'ai_jobs_exam_session_check',
                            'exam_session_members_dependents_cleanup',
                            'exam_session_members_dependents_update_cleanup',
                            'exam_resources_dependents_update_cleanup', 'quiz_attempts_immutable',
                            'ai_jobs_cache_fingerprint_default')
                        """).query(Integer.class).single();

        assertThat(versions).containsExactly("001", "002", "003", "004", "005", "006", "007", "008", "009", "010", "011", "012");
        assertThat(requiredTables).isEqualTo(18);
        assertThat(requiredIndexes).hasSize(10);
        assertThat(jobColumns).isEqualTo(7);
        assertThat(nullableSourceParents).isEqualTo(4);
        assertThat(requiredConstraints).isEqualTo(12);
        assertThat(requiredTriggers).isEqualTo(9);
        System.out.printf("SCHEMA_DB migrations=%s tables=%d indexes=%d constraints=%d triggers=%d "
                        + "jobColumns=%d nullablePageBlockParents=%d result=PASS%n", versions, requiredTables,
                requiredIndexes.size(), requiredConstraints, requiredTriggers, jobColumns, nullableSourceParents);
    }

    @Test
    void upgradesExistingSessionAttemptsAndProgress_toV011Scope() {
        String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.sql("CREATE SCHEMA " + schema).update();
        String url = POSTGRES.getJdbcUrl() + "&currentSchema=" + schema + ",public";
        Flyway throughV010 = Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).target(MigrationVersion.fromVersion("010")).load();
        throughV010.migrate();
        JdbcClient upgrade = JdbcClient.create(new DriverManagerDataSource(
                url, POSTGRES.getUsername(), POSTGRES.getPassword()));
        SchemaFixture oldFixture = new SchemaFixture(upgrade);
        UUID course = upgrade.sql("SELECT course_id FROM class_sessions WHERE owner_id=:owner AND id=:session")
                .param("owner", oldFixture.ownerId()).param("session", oldFixture.sessionId())
                .query(UUID.class).single();
        UUID question = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        UUID progress = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        upgrade.sql("""
                        INSERT INTO quiz_questions
                            (id,owner_id,course_id,session_id,quiz_scope,question_type,input_version,
                             question_json,answer_json,explanation_json,status,model_id,prompt_version,created_at)
                        VALUES (:id,:owner,:course,:session,'practice','true_false',1,
                            '{"text":"q","sourceRefs":[{"sourceType":"note"}]}',
                            '{"value":true,"sourceRefs":[{"sourceType":"note"}]}',
                            '{"text":"e","sourceRefs":[{"sourceType":"note"}]}',
                            'succeeded','fake','v1',:now)
                        """).param("id", question).param("owner", oldFixture.ownerId()).param("course", course)
                .param("session", oldFixture.sessionId()).param("now", now).update();
        upgrade.sql("""
                        INSERT INTO quiz_attempts
                            (id,owner_id,quiz_question_id,submitted_answer,is_correct,submitted_at)
                        VALUES (:id,:owner,:question,'{"value":true}',true,:now)
                        """).param("id", attempt).param("owner", oldFixture.ownerId())
                .param("question", question).param("now", now).update();
        upgrade.sql("""
                        INSERT INTO progress_status
                            (id,owner_id,course_id,session_id,state,correct_count,incorrect_count,
                             last_attempt_at,updated_at)
                        VALUES (:id,:owner,:course,:session,'in_progress',1,0,:now,:now)
                        """).param("id", progress).param("owner", oldFixture.ownerId()).param("course", course)
                .param("session", oldFixture.sessionId()).param("now", now).update();

        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).load().migrate();

        assertThat(upgrade.sql("""
                        SELECT count(*) FROM quiz_attempts
                        WHERE id=:id AND owner_id=:owner AND course_id=:course
                          AND session_id=:session AND exam_id IS NULL AND quiz_question_id=:question
                        """).param("id", attempt).param("owner", oldFixture.ownerId()).param("course", course)
                .param("session", oldFixture.sessionId()).param("question", question)
                .query(Integer.class).single()).isOne();
        assertThat(upgrade.sql("SELECT count(*) FROM progress_status WHERE id=:id AND session_id=:session "
                        + "AND exam_id IS NULL").param("id", progress).param("session", oldFixture.sessionId())
                .query(Integer.class).single()).isOne();
        assertThatThrownBy(() -> upgrade.sql("UPDATE quiz_attempts SET is_correct=false WHERE id=:id")
                .param("id", attempt).update()).isInstanceOf(DataAccessException.class);
    }

    @Test
    void upgradesV011JobWithLongLegacyIdempotencyKey_withoutTruncation() {
        String schema = "usage_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String url = POSTGRES.getJdbcUrl() + "&currentSchema=" + schema + ",public";
        Flyway throughV011 = Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).target(MigrationVersion.fromVersion("011")).load();
        throughV011.migrate();
        JdbcClient upgrade = JdbcClient.create(new DriverManagerDataSource(
                url, POSTGRES.getUsername(), POSTGRES.getPassword()));
        SchemaFixture oldFixture = new SchemaFixture(upgrade);
        UUID job = UUID.randomUUID();
        String legacyKey = "legacy-" + "x".repeat(193);
        oldFixture.insertJob(job, oldFixture.materialId(), null, legacyKey);

        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).load().migrate();

        assertThat(upgrade.sql("SELECT idempotency_key FROM ai_jobs WHERE id=:id")
                .param("id", job).query(String.class).single()).isEqualTo(legacyKey);
        assertThat(upgrade.sql("SELECT length(cache_fingerprint) FROM ai_jobs WHERE id=:id")
                .param("id", job).query(Integer.class).single()).isEqualTo(64);
    }

    @Test
    void preservesExplicitCacheFingerprint_andDefaultsOnlyLegacyStyleInsert() {
        UUID legacyStyle = UUID.randomUUID();
        fixture.insertJob(legacyStyle, fixture.materialId(), null, "legacy-style-key");
        String explicitFingerprint = "a".repeat(64);
        UUID explicit = UUID.randomUUID();
        UUID course = jdbc.sql("SELECT course_id FROM class_sessions WHERE id=:session")
                .param("session", fixture.sessionId()).query(UUID.class).single();
        jdbc.sql("""
                        INSERT INTO ai_jobs
                            (id,owner_id,course_id,session_id,job_type,status,input_version,idempotency_key,
                             attempt_count,max_attempts,material_id,source_hash,cache_fingerprint,created_at)
                        VALUES (:id,:owner,:course,:session,'pdf_extract','succeeded',1,:key,
                                0,3,:material,:hash,:fingerprint,:now)
                        """).param("id", explicit).param("owner", fixture.ownerId())
                .param("course", course).param("session", fixture.sessionId())
                .param("key", "explicit-key").param("material", fixture.materialId())
                .param("hash", "f".repeat(64)).param("fingerprint", explicitFingerprint)
                .param("now", Timestamp.from(Instant.now())).update();

        assertThat(jdbc.sql("SELECT length(cache_fingerprint) FROM ai_jobs WHERE id=:id")
                .param("id", legacyStyle).query(Integer.class).single()).isEqualTo(64);
        assertThat(jdbc.sql("SELECT cache_fingerprint FROM ai_jobs WHERE id=:id")
                .param("id", explicit).query(String.class).single()).isEqualTo(explicitFingerprint);
    }

    @Test
    void retainsOwnerScopedSpeechCleanup_whenOwnerGraphIsDeleted() {
        UUID jobId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO speech_input_cleanups
                            (job_id,owner_id,object_uris,not_before,created_at)
                        VALUES (:job,:owner,ARRAY['gs://bucket/temporary/stt/input.m4a'],now()+interval '25 hours',now())
                        """).param("job", jobId).param("owner", fixture.ownerId()).update();

        fixture.deleteOwner();

        assertThat(jdbc.sql("SELECT owner_id FROM speech_input_cleanups WHERE job_id=:job")
                .param("job", jobId).query(UUID.class).single()).isEqualTo(fixture.ownerId());
        System.out.println("SCHEMA_LIFECYCLE scenario=owner_deletion speechCleanupRetained=1 result=PASS");
    }

    @Test
    void rejectsInkStroke_whenBoundingBoxIsNotNormalized() {
        UUID annotationId = fixture.createAnnotationDocument();

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO ink_strokes
                            (id, annotation_document_id, page_number, tool, color, width_norm,
                             points_json, bbox_norm, created_at)
                        VALUES (:id, :document, 1, 'pen', '#000000', 0.01,
                                '[{"x":0.1,"y":0.1}]',
                                '{"x":0.8,"y":0.2,"width":0.3,"height":0.2}', :now)
                        """).param("id", UUID.randomUUID()).param("document", annotationId)
                .param("now", java.time.OffsetDateTime.now()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        recordFailure("invalidNormalizedBBox");
    }

    @Test
    void rejectsDuplicatePage_whenPastExamIsMaterializedForSameSession() {
        fixture.insertPastExamPage(UUID.randomUUID(), 1);

        assertThatThrownBy(() -> fixture.insertPastExamPage(UUID.randomUUID(), 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        recordFailure("duplicatePastExamPage");
    }

    @Test
    void rejectsPastExamData_whenSessionIsNotSelected() {
        UUID unselectedSessionId = fixture.createUnselectedSession();

        assertThatThrownBy(() -> fixture.insertPastExamPage(UUID.randomUUID(), unselectedSessionId, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> fixture.insertExamResourceJob(UUID.randomUUID(), unselectedSessionId,
                "unselected-session"))
                .isInstanceOf(DataIntegrityViolationException.class);
        recordFailure("unselectedPastExamSession");
    }

    @Test
    void removesPastExamData_whenSelectedSessionMembershipIsRemoved() {
        fixture.insertPastExamDependents();

        fixture.deleteExamSessionMembership();

        assertThat(fixture.examSessionMembershipCount()).isZero();
        assertThat(fixture.pastExamDependentCount()).isZero();
        System.out.println("SCHEMA_LIFECYCLE scenario=selectedSessionRemoval dependents=0 result=PASS");
    }

    @Test
    void removesPastExamData_whenSelectedSessionMembershipChanges() {
        fixture.insertPastExamDependents();
        UUID newSessionId = fixture.createUnselectedSession();

        fixture.updateExamSessionMembership(newSessionId);

        assertThat(fixture.examSessionMembershipCount()).isZero();
        assertThat(fixture.examSessionMembershipCount(newSessionId)).isOne();
        assertThat(fixture.pastExamDependentCount()).isZero();
        System.out.println("SCHEMA_LIFECYCLE scenario=selectedSessionUpdate dependents=0 result=PASS");
    }

    @Test
    void removesPastExamData_whenExamResourceMovesToAnotherExam() {
        fixture.insertPastExamDependents();

        fixture.moveExamResourceToNewExam();

        assertThat(fixture.pastExamDependentCount()).isZero();
        System.out.println("SCHEMA_LIFECYCLE scenario=examResourceExamUpdate dependents=0 result=PASS");
    }

    @Test
    void rejectsDocumentPage_whenSourceParentIsMissingOrMixed() {
        assertThatThrownBy(() -> fixture.insertDocumentPage(null, null, 2))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> fixture.insertDocumentPage(fixture.materialId(), fixture.examResourceId(), 2))
                .isInstanceOf(DataIntegrityViolationException.class);
        recordFailure("parentlessAndMixedDocumentPage");
    }

    @Test
    void rejectsContentBlock_whenSourceParentIsMissingOrMixed() {
        assertThatThrownBy(() -> fixture.insertContentBlock(null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> fixture.insertContentBlock(fixture.materialId(), fixture.examResourceId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        recordFailure("parentlessAndMixedContentBlock");
    }

    @Test
    void rejectsChunk_whenSourceParentIsMissingOrMixed() {
        String sourceRef = """
                {"sourceType":"note","noteId":"00000000-0000-0000-0000-000000000001",
                 "contentBlockId":"00000000-0000-0000-0000-000000000002","paragraphOffset":0}
                """;
        assertThatThrownBy(() -> fixture.insertChunk(null, null, sourceRef))
                .isInstanceOf(DataIntegrityViolationException.class);
        UUID blockId = fixture.insertMaterialBlock();
        UUID segmentId = fixture.insertTranscriptSegment();

        assertThatThrownBy(() -> fixture.insertChunk(blockId, segmentId, sourceRef))
                .isInstanceOf(DataIntegrityViolationException.class);
        recordFailure("parentlessAndMixedChunk");
    }

    @Test
    void rejectsChunk_whenSourceReferenceShapeIsInvalid() {
        UUID blockId = fixture.insertMaterialBlock();

        assertThatThrownBy(() -> fixture.insertChunk(blockId, null, "{}"))
                .isInstanceOf(DataIntegrityViolationException.class);
        recordFailure("invalidChunkSourceRef");
    }

    @Test
    void rejectsJob_whenSourceParentsConflictOrIdempotencyKeyRepeats() {
        assertThatThrownBy(() -> fixture.insertJob(UUID.randomUUID(), fixture.materialId(), fixture.examResourceId(),
                "mixed-parent"))
                .isInstanceOf(DataIntegrityViolationException.class);
        fixture.insertJob(UUID.randomUUID(), fixture.materialId(), null, "same-key");

        assertThatThrownBy(() -> fixture.insertJob(UUID.randomUUID(), fixture.materialId(), null, "same-key"))
                .isInstanceOf(DataIntegrityViolationException.class);
        recordFailure("invalidJobParentAndDuplicateIdempotency");
    }

    @Test
    void rejectsMutation_whenQuizAttemptAlreadyExists() {
        UUID attemptId = insertQuizAttempt();

        assertThatThrownBy(() -> jdbc.sql("UPDATE quiz_attempts SET is_correct = false WHERE id = :id")
                .param("id", attemptId).update()).isInstanceOf(DataAccessException.class);
        recordFailure("immutableQuizAttempt");
    }

    private UUID insertQuizAttempt() {
        UUID owner = fixture.ownerId();
        UUID session = fixture.sessionId();
        UUID course = jdbc.sql("SELECT course_id FROM class_sessions WHERE owner_id=:owner AND id=:session")
                .param("owner", owner).param("session", session).query(UUID.class).single();
        UUID question = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("""
                        INSERT INTO quiz_questions
                            (id,owner_id,course_id,session_id,quiz_scope,question_type,input_version,
                             question_json,answer_json,explanation_json,status,model_id,prompt_version,created_at)
                        VALUES (:id,:owner,:course,:session,'practice','true_false',1,
                            '{"text":"q","sourceRefs":[{"sourceType":"note"}]}',
                            '{"value":true,"sourceRefs":[{"sourceType":"note"}]}',
                            '{"text":"e","sourceRefs":[{"sourceType":"note"}]}',
                            'succeeded','fake','v1',:now)
                        """).param("id", question).param("owner", owner).param("course", course)
                .param("session", session).param("now", now).update();
        jdbc.sql("""
                        INSERT INTO quiz_attempts
                            (id,owner_id,course_id,session_id,quiz_question_id,
                             submitted_answer,is_correct,submitted_at)
                        VALUES (:id,:owner,:course,:session,:question,'{"value":true}',true,:now)
                        """).param("id", attempt).param("owner", owner).param("course", course)
                .param("session", session).param("question", question).param("now", now).update();
        return attempt;
    }

    private void recordFailure(String scenario) {
        System.out.printf("SCHEMA_CONSTRAINT scenario=%s rejected=true result=PASS%n", scenario);
    }

}
