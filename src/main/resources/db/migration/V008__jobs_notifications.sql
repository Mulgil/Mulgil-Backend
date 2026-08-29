CREATE TABLE ai_jobs (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    course_id uuid NOT NULL,
    session_id uuid NOT NULL,
    job_type varchar(32) NOT NULL CHECK (job_type IN
        ('pdf_extract', 'pdf_ocr', 'handwriting_ocr', 'stt', 'chunk_embed', 'preview_generate',
         'review_generate', 'exam_summary_generate', 'exam_quiz_generate', 'notification_send')),
    status varchar(32) NOT NULL CHECK (status IN
        ('created', 'uploaded', 'queued', 'running', 'succeeded', 'failed',
         'needs_user_review', 'cancelled', 'outdated')),
    input_version integer NOT NULL CHECK (input_version >= 1),
    idempotency_key varchar(255) NOT NULL UNIQUE,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    max_attempts integer NOT NULL DEFAULT 3 CHECK (max_attempts >= 1),
    material_id uuid,
    exam_resource_id uuid,
    note_id uuid,
    recording_id uuid,
    exam_id uuid,
    source_hash char(64),
    claimed_by varchar(255),
    last_heartbeat_at timestamptz,
    lease_expires_at timestamptz,
    provider_request_id varchar(255),
    error_code varchar(100),
    error_message varchar(500),
    started_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz NOT NULL,
    FOREIGN KEY (owner_id, course_id, session_id)
        REFERENCES class_sessions(owner_id, course_id, id) ON DELETE CASCADE,
    FOREIGN KEY (owner_id, course_id, session_id, material_id)
        REFERENCES materials(owner_id, course_id, session_id, id) ON DELETE CASCADE,
    FOREIGN KEY (owner_id, course_id, exam_resource_id)
        REFERENCES exam_resources(owner_id, course_id, id) ON DELETE CASCADE,
    FOREIGN KEY (owner_id, course_id, session_id, note_id)
        REFERENCES notes(owner_id, course_id, session_id, id) ON DELETE CASCADE,
    FOREIGN KEY (owner_id, course_id, session_id, recording_id)
        REFERENCES audio_recordings(owner_id, course_id, session_id, id) ON DELETE CASCADE,
    FOREIGN KEY (owner_id, course_id, exam_id)
        REFERENCES exams(owner_id, course_id, id) ON DELETE CASCADE,
    CHECK (attempt_count <= max_attempts),
    CONSTRAINT ai_jobs_source_parent_check CHECK (material_id IS NULL OR exam_resource_id IS NULL),
    CHECK (job_type NOT IN ('pdf_extract', 'pdf_ocr') OR
           (material_id IS NOT NULL)::integer + (exam_resource_id IS NOT NULL)::integer = 1),
    CHECK (job_type <> 'stt' OR recording_id IS NOT NULL),
    CHECK (job_type NOT IN ('exam_summary_generate', 'exam_quiz_generate') OR exam_id IS NOT NULL),
    CHECK (status <> 'running' OR
           (claimed_by IS NOT NULL AND last_heartbeat_at IS NOT NULL AND lease_expires_at IS NOT NULL)),
    CHECK (last_heartbeat_at IS NULL OR lease_expires_at IS NULL OR last_heartbeat_at < lease_expires_at),
    CHECK (started_at IS NULL OR finished_at IS NULL OR started_at <= finished_at)
);

CREATE INDEX ai_jobs_owner_session_idx
    ON ai_jobs(owner_id, course_id, session_id, created_at, id);
CREATE INDEX ai_jobs_queued_claim_idx
    ON ai_jobs(created_at, id) WHERE status = 'queued';
CREATE INDEX ai_jobs_expired_running_idx
    ON ai_jobs(lease_expires_at, id) WHERE status = 'running';
CREATE INDEX ai_jobs_material_idx ON ai_jobs(material_id, input_version) WHERE material_id IS NOT NULL;
CREATE INDEX ai_jobs_exam_resource_idx
    ON ai_jobs(exam_resource_id, input_version) WHERE exam_resource_id IS NOT NULL;

CREATE TRIGGER ai_jobs_exam_session_check
BEFORE INSERT OR UPDATE ON ai_jobs
FOR EACH ROW EXECUTE FUNCTION validate_exam_resource_session();

CREATE FUNCTION delete_exam_session_dependents()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE'
       AND OLD.exam_id IS NOT DISTINCT FROM NEW.exam_id
       AND OLD.session_id IS NOT DISTINCT FROM NEW.session_id
       AND OLD.owner_id IS NOT DISTINCT FROM NEW.owner_id
       AND OLD.course_id IS NOT DISTINCT FROM NEW.course_id THEN
        RETURN NEW;
    END IF;

    DELETE FROM ai_jobs job
    USING exam_resources resource
    WHERE job.exam_resource_id = resource.id
      AND resource.exam_id = OLD.exam_id
      AND job.owner_id = OLD.owner_id
      AND job.course_id = OLD.course_id
      AND job.session_id = OLD.session_id;

    DELETE FROM document_pages page
    USING exam_resources resource
    WHERE page.exam_resource_id = resource.id
      AND resource.exam_id = OLD.exam_id
      AND page.owner_id = OLD.owner_id
      AND page.course_id = OLD.course_id
      AND page.session_id = OLD.session_id;
    IF TG_OP = 'UPDATE' THEN
        RETURN NEW;
    END IF;
    RETURN OLD;
END;
$$;

CREATE TRIGGER exam_session_members_dependents_cleanup
AFTER DELETE ON exam_session_members
FOR EACH ROW EXECUTE FUNCTION delete_exam_session_dependents();

CREATE TRIGGER exam_session_members_dependents_update_cleanup
AFTER UPDATE OF exam_id, session_id, owner_id, course_id ON exam_session_members
FOR EACH ROW EXECUTE FUNCTION delete_exam_session_dependents();

CREATE FUNCTION delete_exam_resource_dependents_on_exam_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.exam_id IS NOT DISTINCT FROM NEW.exam_id THEN
        RETURN NEW;
    END IF;

    DELETE FROM ai_jobs WHERE exam_resource_id = OLD.id;
    DELETE FROM document_pages WHERE exam_resource_id = OLD.id;
    RETURN NEW;
END;
$$;

CREATE TRIGGER exam_resources_dependents_update_cleanup
AFTER UPDATE OF exam_id ON exam_resources
FOR EACH ROW EXECUTE FUNCTION delete_exam_resource_dependents_on_exam_change();

CREATE TABLE device_tokens (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    platform varchar(16) NOT NULL CHECK (platform IN ('android', 'ios')),
    token text NOT NULL UNIQUE CHECK (char_length(btrim(token)) > 0),
    timezone varchar(255) NOT NULL CHECK (char_length(btrim(timezone)) > 0),
    last_seen_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (owner_id, id)
);

CREATE INDEX device_tokens_owner_idx ON device_tokens(owner_id, last_seen_at, id);

CREATE TABLE notifications (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    course_id uuid,
    session_id uuid,
    device_token_id uuid,
    notification_type varchar(32) NOT NULL CHECK (notification_type IN
        ('post_class_reminder', 'processing_complete', 'exam_reminder')),
    title varchar(255) NOT NULL CHECK (char_length(btrim(title)) > 0),
    body varchar(500) NOT NULL CHECK (char_length(btrim(body)) > 0),
    data_json jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(data_json) = 'object'),
    deep_link varchar(1024) NOT NULL CHECK (char_length(btrim(deep_link)) > 0),
    scheduled_at timestamptz NOT NULL,
    sent_at timestamptz,
    read_at timestamptz,
    status varchar(16) NOT NULL CHECK (status IN ('scheduled', 'sent', 'failed', 'cancelled')),
    created_at timestamptz NOT NULL,
    FOREIGN KEY (owner_id, course_id, session_id)
        REFERENCES class_sessions(owner_id, course_id, id) ON DELETE CASCADE,
    FOREIGN KEY (owner_id, device_token_id)
        REFERENCES device_tokens(owner_id, id) ON DELETE SET NULL (device_token_id),
    CHECK ((course_id IS NULL) = (session_id IS NULL)),
    CHECK (sent_at IS NULL OR sent_at >= scheduled_at),
    CHECK (read_at IS NULL OR sent_at IS NOT NULL)
);

CREATE INDEX notifications_owner_idx ON notifications(owner_id, created_at, id);
CREATE INDEX notifications_scheduled_idx
    ON notifications(scheduled_at, id) WHERE status = 'scheduled';
