package com.mulgil.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

final class SchemaFixture {
    private final JdbcClient jdbc;
    private final UUID ownerId = UUID.randomUUID();
    private final UUID courseId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID examId = UUID.randomUUID();
    private final UUID materialId = UUID.randomUUID();
    private final UUID examResourceId = UUID.randomUUID();

    SchemaFixture(JdbcClient jdbc) {
        this.jdbc = jdbc;
        seed();
    }

    UUID materialId() {
        return materialId;
    }

    UUID examResourceId() {
        return examResourceId;
    }

    UUID sessionId() {
        return sessionId;
    }

    UUID createAnnotationDocument() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO annotation_documents
                            (id, owner_id, course_id, session_id, material_id, version, last_left_version,
                             created_at, updated_at)
                        VALUES (:id, :owner, :course, :session, :material, 1, 0, :now, :now)
                        """).param("id", id).param("owner", ownerId).param("course", courseId)
                .param("session", sessionId).param("material", materialId).param("now", now()).update();
        return id;
    }

    UUID insertPastExamPage(UUID id, int pageNumber) {
        return insertPastExamPage(id, sessionId, pageNumber);
    }

    UUID insertPastExamPage(UUID id, UUID targetSessionId, int pageNumber) {
        jdbc.sql("""
                        INSERT INTO document_pages
                            (id, owner_id, course_id, session_id, exam_resource_id, page_number,
                             text_content, text_hash, extraction_method, created_at)
                        VALUES (:id, :owner, :course, :session, :examResource, :page,
                                'exam text', :hash, 'pdf_text', :now)
                        """).param("id", id).param("owner", ownerId).param("course", courseId)
                .param("session", targetSessionId).param("examResource", examResourceId).param("page", pageNumber)
                .param("hash", "a".repeat(64)).param("now", now()).update();
        return id;
    }

    void insertPastExamBlockAndChunk(UUID pageId) {
        UUID blockId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO content_blocks
                            (id, owner_id, course_id, session_id, exam_resource_id, page_id, block_type,
                             text_content, source_hash, created_at)
                        VALUES (:id, :owner, :course, :session, :examResource, :page, 'text',
                                'past exam block', :hash, :now)
                        """).param("id", blockId).param("owner", ownerId).param("course", courseId)
                .param("session", sessionId).param("examResource", examResourceId).param("page", pageId)
                .param("hash", "4".repeat(64)).param("now", now()).update();
        jdbc.sql("""
                        INSERT INTO chunks
                            (id, owner_id, course_id, session_id, content_block_id, chunk_index,
                             text_content, source_ref, source_hash, created_at)
                        VALUES (:id, :owner, :course, :session, :block, 0,
                                'past exam chunk', CAST(:sourceRef AS jsonb), :hash, :now)
                        """).param("id", UUID.randomUUID()).param("owner", ownerId).param("course", courseId)
                .param("session", sessionId).param("block", blockId).param("sourceRef", """
                        {"sourceType":"past_exam","examResourceId":"%s",
                         "contentBlockId":"%s","pageNumber":1}
                        """.formatted(examResourceId, blockId))
                .param("hash", "5".repeat(64)).param("now", now()).update();
    }

    UUID createUnselectedSession() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO class_sessions
                            (id, owner_id, course_id, session_number, title, session_date, created_at, updated_at)
                        VALUES (:id, :owner, :course, 2, 'Unselected', DATE '2026-09-08', :now, :now)
                        """).param("id", id).param("owner", ownerId).param("course", courseId)
                .param("now", now()).update();
        return id;
    }

    void insertDocumentPage(UUID material, UUID examResource, int pageNumber) {
        jdbc.sql("""
                        INSERT INTO document_pages
                            (id, owner_id, course_id, session_id, material_id, exam_resource_id, page_number,
                             text_content, text_hash, extraction_method, created_at)
                        VALUES (:id, :owner, :course, :session, :material, :examResource, :page,
                                'page text', :hash, 'pdf_text', :now)
                        """).param("id", UUID.randomUUID()).param("owner", ownerId).param("course", courseId)
                .param("session", sessionId).param("material", material).param("examResource", examResource)
                .param("page", pageNumber).param("hash", "1".repeat(64)).param("now", now()).update();
    }

    void insertContentBlock(UUID material, UUID examResource) {
        jdbc.sql("""
                        INSERT INTO content_blocks
                            (id, owner_id, course_id, session_id, material_id, exam_resource_id, block_type,
                             text_content, source_hash, created_at)
                        VALUES (:id, :owner, :course, :session, :material, :examResource, 'text',
                                'block text', :hash, :now)
                        """).param("id", UUID.randomUUID()).param("owner", ownerId).param("course", courseId)
                .param("session", sessionId).param("material", material).param("examResource", examResource)
                .param("hash", "2".repeat(64)).param("now", now()).update();
    }

    UUID insertMaterialBlock() {
        UUID pageId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO document_pages
                            (id, owner_id, course_id, session_id, material_id, page_number,
                             text_content, text_hash, extraction_method, created_at)
                        VALUES (:id, :owner, :course, :session, :material, 1,
                                'page text', :hash, 'pdf_text', :now)
                        """).param("id", pageId).param("owner", ownerId).param("course", courseId)
                .param("session", sessionId).param("material", materialId)
                .param("hash", "b".repeat(64)).param("now", now()).update();
        UUID blockId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO content_blocks
                            (id, owner_id, course_id, session_id, material_id, page_id, block_type,
                             text_content, source_hash, created_at)
                        VALUES (:id, :owner, :course, :session, :material, :page, 'text',
                                'block text', :hash, :now)
                        """).param("id", blockId).param("owner", ownerId).param("course", courseId)
                .param("session", sessionId).param("material", materialId).param("page", pageId)
                .param("hash", "c".repeat(64)).param("now", now()).update();
        return blockId;
    }

    UUID insertTranscriptSegment() {
        UUID recordingId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO audio_recordings
                            (id, owner_id, course_id, session_id, object_key, original_filename, mime_type,
                             byte_size, started_at, duration_seconds, version, status, created_at, updated_at)
                        VALUES (:id, :owner, :course, :session, :key, 'audio.m4a', 'audio/m4a', 10,
                                :now, 10, 1, 'uploaded', :now, :now)
                        """).param("id", recordingId).param("owner", ownerId).param("course", courseId)
                .param("session", sessionId).param("key", "schema/" + recordingId).param("now", now()).update();
        UUID segmentId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO transcript_segments
                            (id, owner_id, course_id, session_id, recording_id, start_ms, end_ms,
                             text_content, provider, model_id, source_hash, created_at)
                        VALUES (:id, :owner, :course, :session, :recording, 0, 1000,
                                'segment text', 'fake', 'fake-model', :hash, :now)
                        """).param("id", segmentId).param("owner", ownerId).param("course", courseId)
                .param("session", sessionId).param("recording", recordingId)
                .param("hash", "d".repeat(64)).param("now", now()).update();
        return segmentId;
    }

    void insertChunk(UUID blockId, UUID segmentId, String sourceRef) {
        jdbc.sql("""
                        INSERT INTO chunks
                            (id, owner_id, course_id, session_id, content_block_id, transcript_segment_id,
                             chunk_index, text_content, source_ref, source_hash, created_at)
                        VALUES (:id, :owner, :course, :session, :block, :segment, 0, 'chunk text',
                                CAST(:sourceRef AS jsonb), :hash, :now)
                        """).param("id", UUID.randomUUID()).param("owner", ownerId).param("course", courseId)
                .param("session", sessionId).param("block", blockId).param("segment", segmentId)
                .param("sourceRef", sourceRef).param("hash", "e".repeat(64)).param("now", now()).update();
    }

    void insertJob(UUID id, UUID material, UUID examResource, String key) {
        jdbc.sql("""
                        INSERT INTO ai_jobs
                            (id, owner_id, course_id, session_id, job_type, status, input_version,
                             idempotency_key, attempt_count, max_attempts, material_id, exam_resource_id,
                             source_hash, created_at)
                        VALUES (:id, :owner, :course, :session, 'pdf_extract', 'queued', 1,
                                :key, 0, 3, :material, :examResource, :hash, :now)
                        """).param("id", id).param("owner", ownerId).param("course", courseId)
                .param("session", sessionId).param("key", key).param("material", material)
                .param("examResource", examResource).param("hash", "f".repeat(64)).param("now", now()).update();
    }

    void insertExamResourceJob(UUID id, UUID targetSessionId, String key) {
        jdbc.sql("""
                        INSERT INTO ai_jobs
                            (id, owner_id, course_id, session_id, job_type, status, input_version,
                             idempotency_key, attempt_count, max_attempts, exam_resource_id,
                             source_hash, created_at)
                        VALUES (:id, :owner, :course, :session, 'pdf_extract', 'queued', 1,
                                :key, 0, 3, :examResource, :hash, :now)
                        """).param("id", id).param("owner", ownerId).param("course", courseId)
                .param("session", targetSessionId).param("key", key).param("examResource", examResourceId)
                .param("hash", "3".repeat(64)).param("now", now()).update();
    }

    void deleteExamSessionMembership() {
        jdbc.sql("DELETE FROM exam_session_members WHERE exam_id = :exam AND session_id = :session")
                .param("exam", examId).param("session", sessionId).update();
    }

    int examSessionMembershipCount() {
        return jdbc.sql("SELECT count(*) FROM exam_session_members WHERE exam_id = :exam AND session_id = :session")
                .param("exam", examId).param("session", sessionId).query(Integer.class).single();
    }

    int orphanPastExamDependentCount() {
        return jdbc.sql("""
                        SELECT
                            (SELECT count(*) FROM document_pages dp
                             WHERE dp.exam_resource_id = :examResource
                               AND NOT EXISTS (
                                   SELECT 1 FROM exam_session_members esm
                                   WHERE esm.exam_id = :exam AND esm.session_id = dp.session_id))
                          + (SELECT count(*) FROM ai_jobs j
                             WHERE j.exam_resource_id = :examResource
                               AND NOT EXISTS (
                                   SELECT 1 FROM exam_session_members esm
                                   WHERE esm.exam_id = :exam AND esm.session_id = j.session_id))
                          + (SELECT count(*) FROM content_blocks block
                             WHERE block.exam_resource_id = :examResource
                               AND NOT EXISTS (
                                   SELECT 1 FROM exam_session_members esm
                                   WHERE esm.exam_id = :exam AND esm.session_id = block.session_id))
                          + (SELECT count(*) FROM chunks chunk
                             JOIN content_blocks block ON block.id = chunk.content_block_id
                             WHERE block.exam_resource_id = :examResource
                               AND NOT EXISTS (
                                   SELECT 1 FROM exam_session_members esm
                                   WHERE esm.exam_id = :exam AND esm.session_id = chunk.session_id))
                        """).param("examResource", examResourceId).param("exam", examId)
                .query(Integer.class).single();
    }

    UUID insertQuizAttempt() {
        UUID questionId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO quiz_questions
                            (id, owner_id, course_id, session_id, quiz_scope, question_type, input_version,
                             question_json, answer_json, explanation_json, status, model_id,
                             prompt_version, created_at)
                        VALUES (:id, :owner, :course, :session, 'practice', 'true_false', 1,
                                '{}', '{}', '{}', 'created', 'fake-model', 'v1', :now)
                        """).param("id", questionId).param("owner", ownerId).param("course", courseId)
                .param("session", sessionId).param("now", now()).update();
        UUID attemptId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO quiz_attempts
                            (id, owner_id, quiz_question_id, submitted_answer, is_correct, submitted_at)
                        VALUES (:id, :owner, :question, '{"value":true}', true, :now)
                        """).param("id", attemptId).param("owner", ownerId).param("question", questionId)
                .param("now", now()).update();
        return attemptId;
    }

    private void seed() {
        jdbc.sql("INSERT INTO users VALUES (:id, 'google', :subject, :email, 'Schema Owner', :now)")
                .param("id", ownerId).param("subject", ownerId.toString())
                .param("email", ownerId + "@example.com").param("now", now()).update();
        jdbc.sql("INSERT INTO courses VALUES (:id, :owner, 'Schema', NULL, NULL, :now, :now)")
                .param("id", courseId).param("owner", ownerId).param("now", now()).update();
        jdbc.sql("""
                        INSERT INTO class_sessions
                            (id, owner_id, course_id, session_number, title, session_date, created_at, updated_at)
                        VALUES (:id, :owner, :course, 1, 'Session', DATE '2026-09-01', :now, :now)
                        """).param("id", sessionId).param("owner", ownerId).param("course", courseId)
                .param("now", now()).update();
        jdbc.sql("""
                        INSERT INTO exams (id, owner_id, course_id, title, exam_at, created_at, updated_at)
                        VALUES (:id, :owner, :course, 'Exam', :now, :now, :now)
                        """).param("id", examId).param("owner", ownerId).param("course", courseId)
                .param("now", now()).update();
        jdbc.sql("INSERT INTO exam_session_members VALUES (:exam, :session, :owner, :course, :now)")
                .param("exam", examId).param("session", sessionId).param("owner", ownerId)
                .param("course", courseId).param("now", now()).update();
        jdbc.sql("""
                        INSERT INTO materials
                            (id, owner_id, course_id, session_id, source_phase, object_key, original_filename,
                             mime_type, byte_size, version, status, created_at, updated_at)
                        VALUES (:id, :owner, :course, :session, 'preview_pdf', :key, 'source.pdf',
                                'application/pdf', 10, 1, 'uploaded', :now, :now)
                        """).param("id", materialId).param("owner", ownerId).param("course", courseId)
                .param("session", sessionId).param("key", "schema/" + materialId).param("now", now()).update();
        jdbc.sql("""
                        INSERT INTO exam_resources
                            (id, owner_id, course_id, exam_id, resource_type, object_key, original_filename,
                             mime_type, byte_size, status, created_at, updated_at)
                        VALUES (:id, :owner, :course, :exam, 'past_exam', :key, 'exam.pdf',
                                'application/pdf', 10, 'uploaded', :now, :now)
                        """).param("id", examResourceId).param("owner", ownerId).param("course", courseId)
                .param("exam", examId).param("key", "schema/" + examResourceId).param("now", now()).update();
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
