ALTER TABLE exam_resources
    ADD CONSTRAINT exam_resources_owner_course_id_unique UNIQUE (owner_id, course_id, id);
ALTER TABLE notes
    ADD CONSTRAINT notes_scope_id_unique UNIQUE (owner_id, course_id, session_id, id);
ALTER TABLE audio_recordings
    ADD CONSTRAINT audio_recordings_scope_id_unique UNIQUE (owner_id, course_id, session_id, id);

CREATE TABLE document_pages (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    course_id uuid NOT NULL,
    session_id uuid NOT NULL,
    material_id uuid,
    exam_resource_id uuid,
    page_number integer NOT NULL CHECK (page_number >= 1),
    text_content text NOT NULL DEFAULT '',
    text_hash char(64) NOT NULL,
    extraction_method varchar(32) NOT NULL CHECK (extraction_method IN ('pdf_text', 'ocr')),
    created_at timestamptz NOT NULL,
    FOREIGN KEY (owner_id, course_id, session_id)
        REFERENCES class_sessions(owner_id, course_id, id) ON DELETE CASCADE,
    FOREIGN KEY (owner_id, course_id, session_id, material_id)
        REFERENCES materials(owner_id, course_id, session_id, id) ON DELETE CASCADE,
    FOREIGN KEY (owner_id, course_id, exam_resource_id)
        REFERENCES exam_resources(owner_id, course_id, id) ON DELETE CASCADE,
    UNIQUE (owner_id, course_id, session_id, id),
    CONSTRAINT document_pages_exactly_one_parent CHECK (
        (material_id IS NOT NULL)::integer + (exam_resource_id IS NOT NULL)::integer = 1
    )
);

CREATE UNIQUE INDEX document_pages_material_page_uidx
    ON document_pages(material_id, page_number) WHERE material_id IS NOT NULL;
CREATE UNIQUE INDEX document_pages_exam_session_page_uidx
    ON document_pages(exam_resource_id, session_id, page_number) WHERE exam_resource_id IS NOT NULL;
CREATE INDEX document_pages_owner_session_idx
    ON document_pages(owner_id, course_id, session_id, page_number, id);

CREATE FUNCTION validate_exam_resource_session()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.exam_resource_id IS NOT NULL AND NOT EXISTS (
        SELECT 1
        FROM exam_resources er
        JOIN exam_session_members esm
          ON esm.exam_id = er.exam_id
         AND esm.owner_id = er.owner_id
         AND esm.course_id = er.course_id
        WHERE er.id = NEW.exam_resource_id
          AND er.owner_id = NEW.owner_id
          AND er.course_id = NEW.course_id
          AND esm.session_id = NEW.session_id
    ) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            CONSTRAINT = 'exam_resource_selected_session_check',
            MESSAGE = 'exam resource must be materialized for a selected exam session';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER document_pages_exam_session_check
BEFORE INSERT OR UPDATE ON document_pages
FOR EACH ROW EXECUTE FUNCTION validate_exam_resource_session();

CREATE TABLE content_blocks (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    course_id uuid NOT NULL,
    session_id uuid NOT NULL,
    material_id uuid,
    exam_resource_id uuid,
    note_id uuid,
    handwriting_block_id uuid,
    page_id uuid,
    block_type varchar(32) NOT NULL CHECK (block_type IN
        ('text', 'table', 'annotation', 'handwriting', 'image', 'chart')),
    text_content text NOT NULL CHECK (char_length(btrim(text_content)) > 0),
    bbox_norm jsonb CHECK (bbox_norm IS NULL OR is_normalized_bbox(bbox_norm)),
    paragraph_offset integer CHECK (paragraph_offset IS NULL OR paragraph_offset >= 0),
    provider varchar(100),
    model_id varchar(255),
    source_hash char(64) NOT NULL,
    created_at timestamptz NOT NULL,
    FOREIGN KEY (owner_id, course_id, session_id)
        REFERENCES class_sessions(owner_id, course_id, id) ON DELETE CASCADE,
    FOREIGN KEY (owner_id, course_id, session_id, material_id)
        REFERENCES materials(owner_id, course_id, session_id, id) ON DELETE CASCADE,
    FOREIGN KEY (owner_id, course_id, exam_resource_id)
        REFERENCES exam_resources(owner_id, course_id, id) ON DELETE CASCADE,
    FOREIGN KEY (owner_id, course_id, session_id, note_id)
        REFERENCES notes(owner_id, course_id, session_id, id) ON DELETE CASCADE,
    FOREIGN KEY (owner_id, course_id, session_id, handwriting_block_id)
        REFERENCES handwriting_blocks(owner_id, course_id, session_id, id) ON DELETE CASCADE,
    FOREIGN KEY (owner_id, course_id, session_id, page_id)
        REFERENCES document_pages(owner_id, course_id, session_id, id) ON DELETE CASCADE,
    UNIQUE (owner_id, course_id, session_id, id),
    CONSTRAINT content_blocks_exactly_one_source CHECK (
        (material_id IS NOT NULL)::integer
        + (exam_resource_id IS NOT NULL)::integer
        + (note_id IS NOT NULL)::integer
        + (handwriting_block_id IS NOT NULL)::integer = 1
    ),
    CHECK (
        ((material_id IS NOT NULL OR exam_resource_id IS NOT NULL) AND page_id IS NOT NULL)
        OR ((note_id IS NOT NULL OR handwriting_block_id IS NOT NULL) AND page_id IS NULL)
    ),
    CHECK ((note_id IS NOT NULL) = (paragraph_offset IS NOT NULL))
);

CREATE INDEX content_blocks_owner_session_idx
    ON content_blocks(owner_id, course_id, session_id, block_type, id);
CREATE INDEX content_blocks_material_idx ON content_blocks(material_id, page_id) WHERE material_id IS NOT NULL;
CREATE INDEX content_blocks_exam_resource_idx
    ON content_blocks(exam_resource_id, session_id, page_id) WHERE exam_resource_id IS NOT NULL;
CREATE INDEX content_blocks_note_idx ON content_blocks(note_id, paragraph_offset) WHERE note_id IS NOT NULL;

CREATE FUNCTION validate_content_block_source()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    page_material_id uuid;
    page_exam_resource_id uuid;
BEGIN
    IF NEW.page_id IS NOT NULL THEN
        SELECT material_id, exam_resource_id INTO page_material_id, page_exam_resource_id
        FROM document_pages WHERE id = NEW.page_id;
        IF page_material_id IS DISTINCT FROM NEW.material_id
           OR page_exam_resource_id IS DISTINCT FROM NEW.exam_resource_id THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                CONSTRAINT = 'content_blocks_page_parent_check',
                MESSAGE = 'content block page must have the same source parent';
        END IF;
    END IF;
    IF NEW.handwriting_block_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM handwriting_blocks
        WHERE id = NEW.handwriting_block_id AND status = 'confirmed'
    ) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            CONSTRAINT = 'content_blocks_confirmed_handwriting_check',
            MESSAGE = 'only confirmed handwriting can become content';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER content_blocks_source_check
BEFORE INSERT OR UPDATE ON content_blocks
FOR EACH ROW EXECUTE FUNCTION validate_content_block_source();
CREATE TRIGGER content_blocks_exam_session_check
BEFORE INSERT OR UPDATE ON content_blocks
FOR EACH ROW EXECUTE FUNCTION validate_exam_resource_session();

CREATE TABLE transcript_segments (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    course_id uuid NOT NULL,
    session_id uuid NOT NULL,
    recording_id uuid NOT NULL,
    start_ms bigint NOT NULL CHECK (start_ms >= 0),
    end_ms bigint NOT NULL,
    text_content text NOT NULL CHECK (char_length(btrim(text_content)) > 0),
    confidence numeric CHECK (confidence BETWEEN 0 AND 1),
    provider varchar(100) NOT NULL,
    model_id varchar(255) NOT NULL,
    source_hash char(64) NOT NULL,
    created_at timestamptz NOT NULL,
    FOREIGN KEY (owner_id, course_id, session_id)
        REFERENCES class_sessions(owner_id, course_id, id) ON DELETE CASCADE,
    FOREIGN KEY (owner_id, course_id, session_id, recording_id)
        REFERENCES audio_recordings(owner_id, course_id, session_id, id) ON DELETE CASCADE,
    UNIQUE (owner_id, course_id, session_id, id),
    UNIQUE (recording_id, start_ms, end_ms, source_hash),
    CHECK (start_ms < end_ms)
);

CREATE INDEX transcript_segments_owner_session_idx
    ON transcript_segments(owner_id, course_id, session_id, start_ms, id);
CREATE INDEX transcript_segments_recording_idx
    ON transcript_segments(recording_id, start_ms, end_ms);
