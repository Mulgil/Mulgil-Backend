CREATE TABLE materials (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    course_id uuid NOT NULL,
    session_id uuid NOT NULL,
    source_phase varchar(32) NOT NULL CHECK (source_phase IN ('preview_pdf', 'review_pdf')),
    object_key varchar(1024) NOT NULL UNIQUE,
    original_filename varchar(255) NOT NULL,
    mime_type varchar(255) NOT NULL CHECK (mime_type = 'application/pdf'),
    byte_size bigint NOT NULL CHECK (byte_size > 0),
    page_count integer CHECK (page_count BETWEEN 1 AND 150),
    checksum char(64),
    version integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    status varchar(32) NOT NULL CHECK (status IN
        ('created', 'uploaded', 'queued', 'running', 'succeeded', 'failed',
         'needs_user_review', 'cancelled', 'outdated')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    FOREIGN KEY (owner_id, course_id, session_id)
        REFERENCES class_sessions(owner_id, course_id, id) ON DELETE CASCADE
);

CREATE INDEX materials_owner_session_idx ON materials(owner_id, session_id, status, version);

CREATE TABLE notes (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    course_id uuid NOT NULL,
    session_id uuid NOT NULL,
    body_markdown text NOT NULL DEFAULT '',
    version integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    last_left_version integer NOT NULL DEFAULT 0 CHECK (last_left_version >= 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    FOREIGN KEY (owner_id, course_id, session_id)
        REFERENCES class_sessions(owner_id, course_id, id) ON DELETE CASCADE
);

CREATE INDEX notes_owner_session_idx ON notes(owner_id, session_id, version);

CREATE TABLE audio_recordings (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    course_id uuid,
    session_id uuid,
    object_key varchar(1024) NOT NULL UNIQUE,
    original_filename varchar(255) NOT NULL,
    mime_type varchar(255) NOT NULL CHECK (mime_type IN ('audio/m4a', 'audio/mp4')),
    byte_size bigint NOT NULL CHECK (byte_size > 0),
    checksum char(64),
    started_at timestamptz NOT NULL,
    duration_seconds bigint CHECK (duration_seconds BETWEEN 1 AND 10800),
    version integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    status varchar(32) NOT NULL CHECK (status IN
        ('created', 'uploaded', 'queued', 'running', 'succeeded', 'failed',
         'needs_user_review', 'cancelled', 'outdated')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CHECK ((course_id IS NULL) = (session_id IS NULL)),
    FOREIGN KEY (owner_id, course_id, session_id)
        REFERENCES class_sessions(owner_id, course_id, id) ON DELETE CASCADE
);

CREATE INDEX audio_recordings_owner_session_idx
    ON audio_recordings(owner_id, session_id, status, version);

CREATE TABLE exam_resources (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    course_id uuid NOT NULL,
    exam_id uuid NOT NULL,
    resource_type varchar(32) NOT NULL CHECK (resource_type = 'past_exam'),
    object_key varchar(1024) NOT NULL UNIQUE,
    original_filename varchar(255) NOT NULL,
    mime_type varchar(255) NOT NULL CHECK (mime_type = 'application/pdf'),
    byte_size bigint NOT NULL CHECK (byte_size > 0),
    page_count integer CHECK (page_count BETWEEN 1 AND 150),
    checksum char(64),
    status varchar(32) NOT NULL CHECK (status IN
        ('created', 'uploaded', 'queued', 'running', 'succeeded', 'failed',
         'needs_user_review', 'cancelled', 'outdated')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    FOREIGN KEY (owner_id, course_id, exam_id)
        REFERENCES exams(owner_id, course_id, id) ON DELETE CASCADE
);

CREATE INDEX exam_resources_owner_exam_idx ON exam_resources(owner_id, exam_id, status);
