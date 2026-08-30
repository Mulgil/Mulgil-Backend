DROP TRIGGER quiz_attempts_immutable ON quiz_attempts;

ALTER TABLE quiz_questions
    ADD CONSTRAINT quiz_questions_session_scope_key
        UNIQUE (owner_id, course_id, session_id, id),
    ADD CONSTRAINT quiz_questions_exam_scope_key
        UNIQUE (owner_id, course_id, exam_id, id);

ALTER TABLE quiz_attempts
    ADD COLUMN course_id uuid,
    ADD COLUMN session_id uuid,
    ADD COLUMN exam_id uuid;

UPDATE quiz_attempts attempt
SET course_id = question.course_id,
    session_id = question.session_id,
    exam_id = question.exam_id
FROM quiz_questions question
WHERE question.owner_id = attempt.owner_id
  AND question.id = attempt.quiz_question_id;

ALTER TABLE quiz_attempts
    ALTER COLUMN course_id SET NOT NULL,
    DROP CONSTRAINT quiz_attempts_owner_id_quiz_question_id_fkey,
    ADD CONSTRAINT quiz_attempts_exactly_one_scope
        CHECK ((session_id IS NOT NULL)::integer + (exam_id IS NOT NULL)::integer = 1),
    ADD CONSTRAINT quiz_attempts_session_parent_fkey
        FOREIGN KEY (owner_id, course_id, session_id)
        REFERENCES class_sessions(owner_id, course_id, id),
    ADD CONSTRAINT quiz_attempts_exam_parent_fkey
        FOREIGN KEY (owner_id, course_id, exam_id)
        REFERENCES exams(owner_id, course_id, id),
    ADD CONSTRAINT quiz_attempts_session_question_fkey
        FOREIGN KEY (owner_id, course_id, session_id, quiz_question_id)
        REFERENCES quiz_questions(owner_id, course_id, session_id, id),
    ADD CONSTRAINT quiz_attempts_exam_question_fkey
        FOREIGN KEY (owner_id, course_id, exam_id, quiz_question_id)
        REFERENCES quiz_questions(owner_id, course_id, exam_id, id);

ALTER TABLE progress_status
    ADD COLUMN exam_id uuid,
    ALTER COLUMN session_id DROP NOT NULL,
    DROP CONSTRAINT progress_status_owner_id_quiz_question_id_fkey,
    ADD CONSTRAINT progress_status_exactly_one_scope
        CHECK ((session_id IS NOT NULL)::integer + (exam_id IS NOT NULL)::integer = 1),
    ADD CONSTRAINT progress_status_exam_parent_fkey
        FOREIGN KEY (owner_id, course_id, exam_id)
        REFERENCES exams(owner_id, course_id, id) ON DELETE CASCADE,
    ADD CONSTRAINT progress_status_session_question_fkey
        FOREIGN KEY (owner_id, course_id, session_id, quiz_question_id)
        REFERENCES quiz_questions(owner_id, course_id, session_id, id) ON DELETE CASCADE,
    ADD CONSTRAINT progress_status_exam_question_fkey
        FOREIGN KEY (owner_id, course_id, exam_id, quiz_question_id)
        REFERENCES quiz_questions(owner_id, course_id, exam_id, id) ON DELETE CASCADE;

DROP INDEX progress_status_question_uidx;
DROP INDEX progress_status_session_uidx;

CREATE UNIQUE INDEX progress_status_session_question_uidx
    ON progress_status(owner_id, course_id, session_id, quiz_question_id)
    WHERE session_id IS NOT NULL AND quiz_question_id IS NOT NULL;
CREATE UNIQUE INDEX progress_status_exam_question_uidx
    ON progress_status(owner_id, course_id, exam_id, quiz_question_id)
    WHERE exam_id IS NOT NULL AND quiz_question_id IS NOT NULL;
CREATE UNIQUE INDEX progress_status_session_uidx
    ON progress_status(owner_id, course_id, session_id)
    WHERE session_id IS NOT NULL AND quiz_question_id IS NULL;
CREATE UNIQUE INDEX progress_status_exam_uidx
    ON progress_status(owner_id, course_id, exam_id)
    WHERE exam_id IS NOT NULL AND quiz_question_id IS NULL;
CREATE INDEX progress_status_owner_exam_idx
    ON progress_status(owner_id, course_id, exam_id, updated_at, id);

CREATE TRIGGER quiz_attempts_immutable
BEFORE UPDATE OR DELETE ON quiz_attempts
FOR EACH ROW EXECUTE FUNCTION reject_quiz_attempt_mutation();
