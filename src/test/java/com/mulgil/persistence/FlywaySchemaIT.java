package com.mulgil.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
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
    void appliesV001ThroughV008_whenDatabaseIsFresh() {
        List<String> versions = jdbc.sql("SELECT version FROM flyway_schema_history ORDER BY installed_rank")
                .query(String.class).list();
        Integer requiredTables = jdbc.sql("""
                        SELECT count(*) FROM information_schema.tables
                        WHERE table_schema = 'public' AND table_name IN (
                            'annotation_documents', 'ink_strokes', 'emphasis_regions', 'handwriting_blocks',
                            'document_pages', 'content_blocks', 'transcript_segments', 'chunks', 'summaries',
                            'mindmaps', 'quiz_questions', 'quiz_attempts', 'progress_status', 'ai_jobs',
                            'device_tokens', 'notifications')
                        """).query(Integer.class).single();
        List<String> requiredIndexes = jdbc.sql("""
                        SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND indexname IN (
                            'document_pages_material_page_uidx', 'document_pages_exam_session_page_uidx',
                            'chunks_embedding_hnsw_idx', 'ai_jobs_queued_claim_idx',
                            'ai_jobs_expired_running_idx', 'notifications_scheduled_idx') ORDER BY indexname
                        """).query(String.class).list();
        Integer jobColumns = jdbc.sql("""
                        SELECT count(*) FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = 'ai_jobs' AND (
                            (column_name IN ('exam_resource_id', 'source_hash') AND is_nullable = 'YES')
                            OR (column_name = 'input_version' AND data_type = 'integer')
                            OR column_name IN ('claimed_by', 'last_heartbeat_at', 'lease_expires_at'))
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
                            'ai_jobs_source_parent_check', 'ai_jobs_idempotency_key_key')
                        """).query(Integer.class).single();
        Integer requiredTriggers = jdbc.sql("""
                        SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal AND tgname IN (
                            'document_pages_exam_session_check', 'content_blocks_source_check',
                            'content_blocks_exam_session_check', 'ai_jobs_exam_session_check',
                            'exam_session_members_dependents_cleanup', 'quiz_attempts_immutable')
                        """).query(Integer.class).single();

        assertThat(versions).containsExactly("001", "002", "003", "004", "005", "006", "007", "008");
        assertThat(requiredTables).isEqualTo(16);
        assertThat(requiredIndexes).hasSize(6);
        assertThat(jobColumns).isEqualTo(6);
        assertThat(nullableSourceParents).isEqualTo(4);
        assertThat(requiredConstraints).isEqualTo(6);
        assertThat(requiredTriggers).isEqualTo(6);
        System.out.printf("SCHEMA_DB migrations=%s tables=%d indexes=%d constraints=%d triggers=%d "
                        + "jobColumns=%d nullablePageBlockParents=%d result=PASS%n", versions, requiredTables,
                requiredIndexes.size(), requiredConstraints, requiredTriggers, jobColumns, nullableSourceParents);
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
        UUID pageId = fixture.insertPastExamPage(UUID.randomUUID(), 1);
        fixture.insertPastExamBlockAndChunk(pageId);
        fixture.insertExamResourceJob(UUID.randomUUID(), fixture.sessionId(), "selected-session");

        fixture.deleteExamSessionMembership();

        assertThat(fixture.examSessionMembershipCount()).isZero();
        assertThat(fixture.orphanPastExamDependentCount()).isZero();
        System.out.println("SCHEMA_LIFECYCLE scenario=selectedSessionRemoval dependents=0 result=PASS");
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
        UUID attemptId = fixture.insertQuizAttempt();

        assertThatThrownBy(() -> jdbc.sql("UPDATE quiz_attempts SET is_correct = false WHERE id = :id")
                .param("id", attemptId).update()).isInstanceOf(DataAccessException.class);
        recordFailure("immutableQuizAttempt");
    }

    private void recordFailure(String scenario) {
        System.out.printf("SCHEMA_CONSTRAINT scenario=%s rejected=true result=PASS%n", scenario);
    }

}
