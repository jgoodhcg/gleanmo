CREATE TABLE valid_time_zones (
    name TEXT PRIMARY KEY
);

--;;
INSERT INTO valid_time_zones (name)
SELECT name FROM pg_timezone_names;

--;;
CREATE TABLE exercise (
    id UUID PRIMARY KEY,
    label TEXT NOT NULL,
    type TEXT CHECK (type = 'habit-log'),
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    airtable_log_count TIMESTAMPTZ,
    airtable_id TEXT,
    airtable_ported BOOLEAN,
    source TEXT,
    airtable_exercise_log TEXT,
    notes TEXT,
    airtable_created_time TIMESTAMPTZ,
    user_uuid UUID
);

--;;
CREATE TABLE exercise_session (
    id UUID PRIMARY KEY,
    start_timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    end_timestamp TIMESTAMPTZ,
    type TEXT CHECK (type = 'exercise-session'),
    notes TEXT,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    time_zone TEXT REFERENCES valid_time_zones(name),
    user_id UUID
);

--;;
CREATE TABLE exercise_log (
    id UUID PRIMARY KEY,
    start_timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    end_timestamp TIMESTAMPTZ,
    type TEXT CHECK (type = 'exercise-log'),
    exercise_session_id UUID NOT NULL,
    notes TEXT NOT NULL,
    interval_global_median_ending BOOLEAN NOT NULL,
    airtable_ported BOOLEAN NOT NULL,
    airtable_missing_duration NUMERIC NOT NULL,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    time_zone TEXT REFERENCES valid_time_zones(name),
    user_id UUID,
    CONSTRAINT fk_exercise_session
      FOREIGN KEY (exercise_session_id) REFERENCES exercise_session (id)
);

--;;
CREATE TABLE exercise_set (
    id UUID PRIMARY KEY,
    start_timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    end_timestamp TIMESTAMPTZ,
    type TEXT CHECK (type = 'exercise-set'),
    exercise_id UUID NOT NULL,
    exercise_log_id UUID NOT NULL,
    distance NUMERIC,
    distance_unit TEXT,
    weight_unit TEXT,
    reps INTEGER,
    weight_amount NUMERIC,
    interval_global_median_end BOOLEAN,
    airtable_ported BOOLEAN,
    airtable_exercise_id TEXT,
    airtable_missing_duration NUMERIC,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    time_zone TEXT REFERENCES valid_time_zones(name),
    user_id UUID,
    CONSTRAINT fk_exercise
      FOREIGN KEY (exercise_id) REFERENCES exercise (id),
    CONSTRAINT fk_exercise_log
      FOREIGN KEY (exercise_log_id) REFERENCES exercise_log (id)
);
