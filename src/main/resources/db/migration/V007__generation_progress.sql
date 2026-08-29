CREATE TABLE summaries (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    course_id uuid NOT NULL,
    session_id uuid,
    exam_id uuid,
    summary_type varchar(16) NOT NULL CHECK (summary_type IN ('preview', 'review', 'exam')),
    input_version integer NOT NULL CHECK (input_version >= 1),
    content_json jsonb NOT NULL CHECK (jsonb_typeof(content_json) IN ('object', 'array')),
    source_refs jsonb NOT NULL CHECK (jsonb_typeof(source_refs) = 'array'),
    status varchar(32) NOT NULL CHECK (status IN
        ('created', 'queued', 'running', 'succeeded', 'failed', 'needs_user_review', 'cancelled', 'outdated')),
    model_id varchar(255) NOT NULL,
    prompt_version varchar(100) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    FOREIGN KEY (owner_id, course_id, session_id)
        REFERENCES class_sessions(owner_id, course_id, id) ON DELETE CASCADE,
    FOREIGN KEY (owner_id, course_id, exam_id)
        REFERENCES exams(owner_id, course_id, id) ON DELETE CASCADE,
    CHECK ((session_id IS NOT NULL)::integer + (exam_id IS NOT NULL)::integer = 1),
    CHECK ((summary_type = 'exam') = (exam_id IS NOT NULL)),
    CHECK (status <> 'succeeded' OR jsonb_array_length(source_refs) > 0)
);

CREATE UNIQUE INDEX summaries_session_version_uidx
    ON summaries(owner_id, session_id, summary_type, input_version) WHERE session_id IS NOT NULL;
CREATE UNIQUE INDEX summaries_exam_version_uidx
    ON summaries(owner_id, exam_id, summary_type, input_version) WHERE exam_id IS NOT NULL;
CREATE INDEX summaries_owner_course_idx ON summaries(owner_id, course_id, status, updated_at, id);

CREATE TABLE mindmaps (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    course_id uuid NOT NULL,
    session_id uuid NOT NULL,
    input_version integer NOT NULL CHECK (input_version >= 1),
    nodes_json jsonb NOT NULL CHECK (jsonb_typeof(nodes_json) = 'array'),
    edges_json jsonb NOT NULL CHECK (jsonb_typeof(edges_json) = 'array'),
    source_refs jsonb NOT NULL CHECK (jsonb_typeof(source_refs) = 'array'),
    status varchar(32) NOT NULL CHECK (status IN
        ('created', 'queued', 'running', 'succeeded', 'failed', 'needs_user_review', 'cancelled', 'outdated')),
    model_id varchar(255) NOT NULL,
    prompt_version varchar(100) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    FOREIGN KEY (owner_id, course_id, session_id)
        REFERENCES class_sessions(owner_id, course_id, id) ON DELETE CASCADE,
    UNIQUE (owner_id, session_id, input_version),
    CHECK (status <> 'succeeded' OR jsonb_array_length(source_refs) > 0)
);

CREATE INDEX mindmaps_owner_session_idx ON mindmaps(owner_id, course_id, session_id, status, id);

CREATE TABLE quiz_questions (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    course_id uuid NOT NULL,
    session_id uuid,
    exam_id uuid,
    quiz_scope varchar(32) NOT NULL CHECK (quiz_scope IN ('practice', 'past_exam_based')),
    question_type varchar(32) NOT NULL CHECK (question_type IN ('true_false', 'multiple_choice')),
    input_version integer NOT NULL CHECK (input_version >= 1),
    question_json jsonb NOT NULL CHECK (jsonb_typeof(question_json) = 'object'),
    answer_json jsonb NOT NULL CHECK (jsonb_typeof(answer_json) = 'object'),
    explanation_json jsonb NOT NULL CHECK (jsonb_typeof(explanation_json) = 'object'),
    status varchar(32) NOT NULL CHECK (status IN
        ('created', 'queued', 'running', 'succeeded', 'failed', 'needs_user_review', 'cancelled', 'outdated')),
    model_id varchar(255) NOT NULL,
    prompt_version varchar(100) NOT NULL,
    created_at timestamptz NOT NULL,
    FOREIGN KEY (owner_id, course_id, session_id)
        REFERENCES class_sessions(owner_id, course_id, id) ON DELETE CASCADE,
    FOREIGN KEY (owner_id, course_id, exam_id)
        REFERENCES exams(owner_id, course_id, id) ON DELETE CASCADE,
    UNIQUE (owner_id, id),
    CHECK ((session_id IS NOT NULL)::integer + (exam_id IS NOT NULL)::integer = 1),
    CHECK ((quiz_scope = 'past_exam_based') = (exam_id IS NOT NULL)),
    CHECK (status <> 'succeeded' OR (
        jsonb_typeof(question_json->'sourceRefs') = 'array'
        AND jsonb_array_length(question_json->'sourceRefs') > 0
        AND jsonb_typeof(answer_json->'sourceRefs') = 'array'
        AND jsonb_array_length(answer_json->'sourceRefs') > 0
        AND jsonb_typeof(explanation_json->'sourceRefs') = 'array'
        AND jsonb_array_length(explanation_json->'sourceRefs') > 0
    ))
);

CREATE INDEX quiz_questions_owner_session_idx
    ON quiz_questions(owner_id, course_id, session_id, status, created_at, id);
CREATE INDEX quiz_questions_owner_exam_idx
    ON quiz_questions(owner_id, course_id, exam_id, status, created_at, id);

CREATE TABLE quiz_attempts (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES users(id),
    quiz_question_id uuid NOT NULL,
    submitted_answer jsonb NOT NULL,
    is_correct boolean NOT NULL,
    submitted_at timestamptz NOT NULL,
    FOREIGN KEY (owner_id, quiz_question_id)
        REFERENCES quiz_questions(owner_id, id)
);

CREATE INDEX quiz_attempts_owner_question_idx
    ON quiz_attempts(owner_id, quiz_question_id, submitted_at, id);

CREATE FUNCTION reject_quiz_attempt_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '55000', MESSAGE = 'quiz attempts are immutable';
END;
$$;

CREATE TRIGGER quiz_attempts_immutable
BEFORE UPDATE OR DELETE ON quiz_attempts
FOR EACH ROW EXECUTE FUNCTION reject_quiz_attempt_mutation();

CREATE TABLE progress_status (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    course_id uuid NOT NULL,
    session_id uuid NOT NULL,
    quiz_question_id uuid,
    state varchar(32) NOT NULL CHECK (char_length(btrim(state)) > 0),
    correct_count integer NOT NULL DEFAULT 0 CHECK (correct_count >= 0),
    incorrect_count integer NOT NULL DEFAULT 0 CHECK (incorrect_count >= 0),
    last_attempt_at timestamptz,
    updated_at timestamptz NOT NULL,
    FOREIGN KEY (owner_id, course_id, session_id)
        REFERENCES class_sessions(owner_id, course_id, id) ON DELETE CASCADE,
    FOREIGN KEY (owner_id, quiz_question_id)
        REFERENCES quiz_questions(owner_id, id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX progress_status_question_uidx
    ON progress_status(owner_id, session_id, quiz_question_id) WHERE quiz_question_id IS NOT NULL;
CREATE UNIQUE INDEX progress_status_session_uidx
    ON progress_status(owner_id, session_id) WHERE quiz_question_id IS NULL;
CREATE INDEX progress_status_owner_session_idx
    ON progress_status(owner_id, course_id, session_id, updated_at, id);
