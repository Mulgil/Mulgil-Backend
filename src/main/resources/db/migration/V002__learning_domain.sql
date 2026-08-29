CREATE TABLE courses (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name varchar(100) NOT NULL CHECK (char_length(name) BETWEEN 1 AND 100),
    instructor varchar(100),
    term varchar(50),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (owner_id, id)
);

CREATE INDEX courses_owner_idx ON courses(owner_id, created_at, id);

CREATE TABLE timetable_slots (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    course_id uuid NOT NULL,
    weekday smallint NOT NULL CHECK (weekday BETWEEN 1 AND 7),
    start_time time NOT NULL,
    end_time time NOT NULL,
    timezone varchar(255) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    FOREIGN KEY (owner_id, course_id) REFERENCES courses(owner_id, id) ON DELETE CASCADE,
    CHECK (start_time < end_time)
);

CREATE INDEX timetable_slots_owner_idx ON timetable_slots(owner_id, course_id, weekday, start_time);

CREATE TABLE class_sessions (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    course_id uuid NOT NULL,
    session_number integer NOT NULL CHECK (session_number >= 1),
    title varchar(200) NOT NULL CHECK (char_length(title) BETWEEN 1 AND 200),
    session_date date NOT NULL,
    starts_at timestamptz,
    ends_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    FOREIGN KEY (owner_id, course_id) REFERENCES courses(owner_id, id) ON DELETE CASCADE,
    UNIQUE (course_id, session_number),
    UNIQUE (owner_id, course_id, id),
    CHECK (starts_at IS NULL OR ends_at IS NULL OR starts_at < ends_at)
);

CREATE INDEX class_sessions_owner_course_idx
    ON class_sessions(owner_id, course_id, session_date, session_number);

CREATE TABLE exams (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    course_id uuid NOT NULL,
    title varchar(200) NOT NULL CHECK (char_length(title) BETWEEN 1 AND 200),
    exam_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    FOREIGN KEY (owner_id, course_id) REFERENCES courses(owner_id, id) ON DELETE CASCADE,
    UNIQUE (owner_id, course_id, id)
);

CREATE INDEX exams_owner_course_idx ON exams(owner_id, course_id, exam_at, id);

CREATE TABLE exam_session_members (
    exam_id uuid NOT NULL,
    session_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    course_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (exam_id, session_id),
    FOREIGN KEY (owner_id, course_id, exam_id)
        REFERENCES exams(owner_id, course_id, id) ON DELETE CASCADE,
    FOREIGN KEY (owner_id, course_id, session_id)
        REFERENCES class_sessions(owner_id, course_id, id) ON DELETE CASCADE
);

CREATE INDEX exam_session_members_scope_idx ON exam_session_members(owner_id, course_id, exam_id);
