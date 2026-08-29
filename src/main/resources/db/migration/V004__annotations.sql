CREATE FUNCTION is_normalized_bbox(value jsonb)
RETURNS boolean
LANGUAGE plpgsql
IMMUTABLE
STRICT
AS $$
DECLARE
    x numeric;
    y numeric;
    width numeric;
    height numeric;
BEGIN
    IF jsonb_typeof(value) <> 'object'
       OR value ?& ARRAY['x', 'y', 'width', 'height'] IS FALSE
       OR jsonb_typeof(value->'x') <> 'number'
       OR jsonb_typeof(value->'y') <> 'number'
       OR jsonb_typeof(value->'width') <> 'number'
       OR jsonb_typeof(value->'height') <> 'number' THEN
        RETURN false;
    END IF;
    x := (value->>'x')::numeric;
    y := (value->>'y')::numeric;
    width := (value->>'width')::numeric;
    height := (value->>'height')::numeric;
    RETURN x BETWEEN 0 AND 1
       AND y BETWEEN 0 AND 1
       AND width > 0 AND width <= 1
       AND height > 0 AND height <= 1
       AND x + width <= 1
       AND y + height <= 1;
EXCEPTION WHEN OTHERS THEN
    RETURN false;
END;
$$;

CREATE FUNCTION are_normalized_points(value jsonb)
RETURNS boolean
LANGUAGE plpgsql
IMMUTABLE
STRICT
AS $$
DECLARE
    point jsonb;
BEGIN
    IF jsonb_typeof(value) <> 'array' OR jsonb_array_length(value) = 0 THEN
        RETURN false;
    END IF;
    FOR point IN SELECT element FROM jsonb_array_elements(value) AS element LOOP
        IF jsonb_typeof(point) <> 'object'
           OR point ?& ARRAY['x', 'y'] IS FALSE
           OR jsonb_typeof(point->'x') <> 'number'
           OR jsonb_typeof(point->'y') <> 'number'
           OR (point->>'x')::numeric NOT BETWEEN 0 AND 1
           OR (point->>'y')::numeric NOT BETWEEN 0 AND 1 THEN
            RETURN false;
        END IF;
    END LOOP;
    RETURN true;
EXCEPTION WHEN OTHERS THEN
    RETURN false;
END;
$$;

ALTER TABLE materials
    ADD CONSTRAINT materials_scope_id_unique UNIQUE (owner_id, course_id, session_id, id);

CREATE TABLE annotation_documents (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    course_id uuid NOT NULL,
    session_id uuid NOT NULL,
    material_id uuid NOT NULL UNIQUE,
    version integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    last_left_version integer NOT NULL DEFAULT 0 CHECK (last_left_version BETWEEN 0 AND version),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    FOREIGN KEY (owner_id, course_id, session_id)
        REFERENCES class_sessions(owner_id, course_id, id) ON DELETE CASCADE,
    FOREIGN KEY (owner_id, course_id, session_id, material_id)
        REFERENCES materials(owner_id, course_id, session_id, id) ON DELETE CASCADE,
    UNIQUE (owner_id, course_id, session_id, id)
);

CREATE INDEX annotation_documents_owner_session_idx
    ON annotation_documents(owner_id, course_id, session_id, updated_at);

CREATE TABLE ink_strokes (
    id uuid PRIMARY KEY,
    annotation_document_id uuid NOT NULL REFERENCES annotation_documents(id) ON DELETE CASCADE,
    page_number integer NOT NULL CHECK (page_number >= 1),
    tool varchar(16) NOT NULL CHECK (tool IN ('pen', 'highlight')),
    color varchar(32) NOT NULL CHECK (char_length(color) > 0),
    width_norm numeric NOT NULL CHECK (width_norm > 0 AND width_norm <= 1),
    points_json jsonb NOT NULL CHECK (are_normalized_points(points_json)),
    bbox_norm jsonb NOT NULL CHECK (is_normalized_bbox(bbox_norm)),
    created_at timestamptz NOT NULL
);

CREATE INDEX ink_strokes_document_page_idx
    ON ink_strokes(annotation_document_id, page_number, created_at, id);

CREATE TABLE emphasis_regions (
    id uuid PRIMARY KEY,
    annotation_document_id uuid NOT NULL REFERENCES annotation_documents(id) ON DELETE CASCADE,
    page_number integer NOT NULL CHECK (page_number >= 1),
    bbox_norm jsonb NOT NULL CHECK (is_normalized_bbox(bbox_norm)),
    tap_count integer NOT NULL DEFAULT 0 CHECK (tap_count >= 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE INDEX emphasis_regions_document_page_idx
    ON emphasis_regions(annotation_document_id, page_number, updated_at, id);

CREATE TABLE handwriting_blocks (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    course_id uuid NOT NULL,
    session_id uuid NOT NULL,
    annotation_document_id uuid NOT NULL,
    page_number integer NOT NULL CHECK (page_number >= 1),
    bbox_norm jsonb NOT NULL CHECK (is_normalized_bbox(bbox_norm)),
    stroke_ids jsonb NOT NULL CHECK (
        jsonb_typeof(stroke_ids) = 'array'
        AND jsonb_array_length(stroke_ids) > 0
        AND NOT jsonb_path_exists(stroke_ids, '$[*] ? (@.type() != "string")')
    ),
    ocr_text text,
    confidence numeric CHECK (confidence BETWEEN 0 AND 1),
    status varchar(32) NOT NULL CHECK (status IN
        ('queued', 'succeeded', 'needs_user_review', 'failed', 'confirmed', 'outdated')),
    confirmed_text text,
    input_version integer NOT NULL CHECK (input_version >= 1),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    FOREIGN KEY (owner_id, course_id, session_id)
        REFERENCES class_sessions(owner_id, course_id, id) ON DELETE CASCADE,
    FOREIGN KEY (owner_id, course_id, session_id, annotation_document_id)
        REFERENCES annotation_documents(owner_id, course_id, session_id, id) ON DELETE CASCADE,
    UNIQUE (owner_id, course_id, session_id, id),
    CHECK ((status = 'confirmed') =
           (confirmed_text IS NOT NULL AND char_length(btrim(confirmed_text)) > 0))
);

CREATE INDEX handwriting_blocks_owner_session_status_idx
    ON handwriting_blocks(owner_id, course_id, session_id, status, input_version);
