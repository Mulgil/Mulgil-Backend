CREATE FUNCTION is_valid_source_ref(value jsonb)
RETURNS boolean
LANGUAGE plpgsql
IMMUTABLE
STRICT
AS $$
DECLARE
    source_type text;
BEGIN
    IF jsonb_typeof(value) <> 'object'
       OR jsonb_typeof(value->'sourceType') <> 'string' THEN
        RETURN false;
    END IF;
    source_type := value->>'sourceType';
    IF value ? 'bboxNorm' AND NOT is_normalized_bbox(value->'bboxNorm') THEN
        RETURN false;
    END IF;
    RETURN CASE source_type
        WHEN 'pdf_text' THEN
            jsonb_typeof(value->'contentBlockId') = 'string'
            AND jsonb_typeof(value->'pageNumber') = 'number'
            AND ((value ? 'materialId')::integer + (value ? 'examResourceId')::integer = 1)
        WHEN 'table' THEN
            jsonb_typeof(value->'contentBlockId') = 'string'
            AND jsonb_typeof(value->'pageNumber') = 'number'
            AND ((value ? 'materialId')::integer + (value ? 'examResourceId')::integer = 1)
        WHEN 'past_exam' THEN
            jsonb_typeof(value->'examResourceId') = 'string'
            AND jsonb_typeof(value->'contentBlockId') = 'string'
            AND jsonb_typeof(value->'pageNumber') = 'number'
        WHEN 'handwriting' THEN
            jsonb_typeof(value->'handwritingBlockId') = 'string'
            OR jsonb_typeof(value->'contentBlockId') = 'string'
        WHEN 'note' THEN
            jsonb_typeof(value->'noteId') = 'string'
            AND jsonb_typeof(value->'contentBlockId') = 'string'
            AND jsonb_typeof(value->'paragraphOffset') = 'number'
        WHEN 'transcript' THEN
            jsonb_typeof(value->'recordingId') = 'string'
            AND jsonb_typeof(value->'transcriptSegmentId') = 'string'
            AND jsonb_typeof(value->'startMs') = 'number'
            AND jsonb_typeof(value->'endMs') = 'number'
            AND (value->>'startMs')::bigint >= 0
            AND (value->>'startMs')::bigint < (value->>'endMs')::bigint
        ELSE false
    END;
EXCEPTION WHEN OTHERS THEN
    RETURN false;
END;
$$;

CREATE TABLE chunks (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    course_id uuid NOT NULL,
    session_id uuid NOT NULL,
    content_block_id uuid,
    transcript_segment_id uuid,
    chunk_index integer NOT NULL CHECK (chunk_index >= 0),
    text_content text NOT NULL CHECK (char_length(btrim(text_content)) > 0),
    source_ref jsonb NOT NULL CHECK (is_valid_source_ref(source_ref)),
    embedding vector(768),
    embedding_model varchar(255),
    source_hash char(64) NOT NULL,
    created_at timestamptz NOT NULL,
    FOREIGN KEY (owner_id, course_id, session_id)
        REFERENCES class_sessions(owner_id, course_id, id) ON DELETE CASCADE,
    FOREIGN KEY (owner_id, course_id, session_id, content_block_id)
        REFERENCES content_blocks(owner_id, course_id, session_id, id) ON DELETE CASCADE,
    FOREIGN KEY (owner_id, course_id, session_id, transcript_segment_id)
        REFERENCES transcript_segments(owner_id, course_id, session_id, id) ON DELETE CASCADE,
    CONSTRAINT chunks_exactly_one_source CHECK (
        (content_block_id IS NOT NULL)::integer + (transcript_segment_id IS NOT NULL)::integer = 1
    ),
    CHECK ((embedding IS NULL) = (embedding_model IS NULL))
);

CREATE UNIQUE INDEX chunks_content_block_index_uidx
    ON chunks(content_block_id, chunk_index) WHERE content_block_id IS NOT NULL;
CREATE UNIQUE INDEX chunks_transcript_segment_index_uidx
    ON chunks(transcript_segment_id, chunk_index) WHERE transcript_segment_id IS NOT NULL;
CREATE INDEX chunks_owner_course_session_idx
    ON chunks(owner_id, course_id, session_id, created_at, id);
CREATE INDEX chunks_embedding_hnsw_idx
    ON chunks USING hnsw (embedding vector_cosine_ops) WHERE embedding IS NOT NULL;
