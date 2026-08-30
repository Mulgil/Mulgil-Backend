package com.mulgil.generation;

import com.mulgil.indexing.ContentIndexingService;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

final class GenerationSourceFixtures {
    private final JdbcClient jdbc;
    private final ContentIndexingService indexing;
    private final UUID owner;
    private final UUID course;
    private final UUID session;

    GenerationSourceFixtures(JdbcClient jdbc, ContentIndexingService indexing,
                             UUID owner, UUID course, UUID session) {
        this.jdbc = jdbc;
        this.indexing = indexing;
        this.owner = owner;
        this.course = course;
        this.session = session;
    }

    void addReviewNote(String text, int order) {
        UUID note = UUID.randomUUID();
        UUID block = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now().plusMillis(order));
        jdbc.sql("""
                INSERT INTO notes (id,owner_id,course_id,session_id,body_markdown,version,last_left_version,created_at,updated_at)
                VALUES (:id,:owner,:course,:session,:body,1,1,:now,:now)
                """).param("id", note).param("owner", owner).param("course", course).param("session", session)
                .param("body", text).param("now", now).update();
        jdbc.sql("""
                INSERT INTO content_blocks
                    (id,owner_id,course_id,session_id,note_id,block_type,text_content,paragraph_offset,source_hash,created_at)
                VALUES (:id,:owner,:course,:session,:note,'text',:text,0,:hash,:now)
                """).param("id", block).param("owner", owner).param("course", course).param("session", session)
                .param("note", note).param("text", text).param("hash", ContentIndexingService.sha256(text))
                .param("now", now).update();
        indexing.index(new ContentIndexingService.IndexRequest("note", Map.of(
                "sourceType", "note", "noteId", note.toString(), "contentBlockId", block.toString(),
                "paragraphOffset", 0, "inputVersion", 1), owner, course, session, 1, text));
    }

    void addPreviewMaterial(String text) {
        UUID material = UUID.randomUUID();
        UUID page = UUID.randomUUID();
        UUID block = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        String hash = ContentIndexingService.sha256(text);
        jdbc.sql("""
                INSERT INTO materials
                    (id,owner_id,course_id,session_id,source_phase,object_key,original_filename,mime_type,
                     byte_size,page_count,checksum,version,status,created_at,updated_at)
                VALUES (:id,:owner,:course,:session,'preview_pdf',:key,'preview.pdf','application/pdf',
                        10,1,:hash,1,'uploaded',:now,:now)
                """).param("id", material).param("owner", owner).param("course", course)
                .param("session", session).param("key", "preview/" + material).param("hash", hash)
                .param("now", now).update();
        addDocumentBlock(material, null, page, block, text, hash, now);
        indexing.index(new ContentIndexingService.IndexRequest("pdf_text", Map.of(
                "sourceType", "pdf_text", "materialId", material.toString(),
                "contentBlockId", block.toString(), "pageNumber", 1, "inputVersion", 1),
                owner, course, session, 1, text));
    }

    void addPastExam(UUID exam, String text) {
        UUID resource = UUID.randomUUID();
        UUID page = UUID.randomUUID();
        UUID block = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        String hash = ContentIndexingService.sha256(text);
        jdbc.sql("""
                INSERT INTO exam_resources
                    (id,owner_id,course_id,exam_id,resource_type,object_key,original_filename,mime_type,
                     byte_size,page_count,checksum,status,created_at,updated_at)
                VALUES (:id,:owner,:course,:exam,'past_exam',:key,'past.pdf','application/pdf',10,1,:hash,
                        'uploaded',:now,:now)
                """).param("id", resource).param("owner", owner).param("course", course).param("exam", exam)
                .param("key", "past/" + resource).param("hash", hash).param("now", now).update();
        addDocumentBlock(null, resource, page, block, text, hash, now);
        indexing.index(new ContentIndexingService.IndexRequest("past_exam", Map.of(
                "sourceType", "past_exam", "examResourceId", resource.toString(),
                "contentBlockId", block.toString(), "pageNumber", 1, "inputVersion", 1),
                owner, course, session, 1, text));
    }

    private void addDocumentBlock(UUID material, UUID resource, UUID page, UUID block,
                                  String text, String hash, Timestamp now) {
        jdbc.sql("""
                INSERT INTO document_pages
                    (id,owner_id,course_id,session_id,material_id,exam_resource_id,page_number,text_content,
                     text_hash,extraction_method,created_at)
                VALUES (:id,:owner,:course,:session,:material,:resource,1,:text,:hash,'pdf_text',:now)
                """).param("id", page).param("owner", owner).param("course", course).param("session", session)
                .param("material", material).param("resource", resource).param("text", text)
                .param("hash", hash).param("now", now).update();
        jdbc.sql("""
                INSERT INTO content_blocks
                    (id,owner_id,course_id,session_id,material_id,exam_resource_id,page_id,block_type,
                     text_content,source_hash,created_at)
                VALUES (:id,:owner,:course,:session,:material,:resource,:page,'text',:text,:hash,:now)
                """).param("id", block).param("owner", owner).param("course", course).param("session", session)
                .param("material", material).param("resource", resource).param("page", page)
                .param("text", text).param("hash", hash).param("now", now).update();
    }
}
