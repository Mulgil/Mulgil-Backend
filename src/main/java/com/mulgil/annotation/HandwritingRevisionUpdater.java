package com.mulgil.annotation;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

final class HandwritingRevisionUpdater {
    private final JdbcClient jdbc;

    HandwritingRevisionUpdater(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Integer> advance(UUID documentId, int version, Timestamp now) {
        List<Integer> dirtyPages = dirtyPages(documentId, version);
        List<Integer> changedPages = dirtyPages.isEmpty() ? List.of(-1) : dirtyPages;
        String carryable = """
                handwriting.annotation_document_id=:document AND handwriting.input_version<:version
                AND handwriting.page_number NOT IN (:pages)
                AND handwriting.status IN ('confirmed','needs_user_review')
                """;
        jdbc.sql("""
                UPDATE chunks chunk SET source_ref=jsonb_set(chunk.source_ref,'{inputVersion}',
                    to_jsonb(CAST(:version AS integer)))
                FROM content_blocks content,handwriting_blocks handwriting
                WHERE chunk.content_block_id=content.id AND content.handwriting_block_id=handwriting.id AND
                """ + carryable).param("document", documentId).param("version", version)
                .param("pages", changedPages).update();
        jdbc.sql("UPDATE handwriting_blocks handwriting SET input_version=:version,updated_at=:now WHERE "
                        + carryable).param("document", documentId).param("version", version)
                .param("pages", changedPages).param("now", now).update();
        jdbc.sql("""
                DELETE FROM content_blocks content USING handwriting_blocks handwriting
                WHERE content.handwriting_block_id=handwriting.id
                  AND handwriting.annotation_document_id=:document AND handwriting.input_version<:version
                """).param("document", documentId).param("version", version).update();
        jdbc.sql("""
                UPDATE handwriting_blocks SET status='outdated',confirmed_text=NULL,updated_at=:now
                WHERE annotation_document_id=:document AND input_version<:version AND status<>'outdated'
                """).param("document", documentId).param("version", version).param("now", now).update();
        return dirtyPages;
    }

    private List<Integer> dirtyPages(UUID documentId, int version) {
        return jdbc.sql("""
                SELECT page_number FROM (
                    SELECT DISTINCT stroke.page_number
                    FROM ink_strokes stroke
                    WHERE stroke.annotation_document_id=:document AND stroke.tool='pen'
                      AND NOT EXISTS (
                          SELECT 1 FROM handwriting_blocks prior
                          WHERE prior.annotation_document_id=:document AND prior.input_version<:version
                            AND prior.status IN ('confirmed','needs_user_review')
                            AND prior.stroke_ids @> jsonb_build_array(stroke.id::text)
                            AND prior.created_at>=stroke.created_at
                      )
                    UNION
                    SELECT DISTINCT handwriting.page_number
                    FROM handwriting_blocks handwriting
                    WHERE handwriting.annotation_document_id=:document
                      AND handwriting.input_version<:version AND handwriting.status<>'outdated'
                      AND EXISTS (
                          SELECT 1 FROM jsonb_array_elements_text(handwriting.stroke_ids) stroke_id(value)
                          LEFT JOIN ink_strokes current ON current.id=stroke_id.value::uuid
                            AND current.annotation_document_id=handwriting.annotation_document_id
                          WHERE current.id IS NULL OR current.tool<>'pen'
                            OR current.created_at>handwriting.created_at
                      )
                ) changed ORDER BY page_number
                """).param("document", documentId).param("version", version).query(Integer.class).list();
    }
}
