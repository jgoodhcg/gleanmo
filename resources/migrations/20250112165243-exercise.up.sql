CREATE TABLE exercise (
    id UUID PRIMARY KEY,
    label TEXT NOT NULL,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
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
CREATE INDEX idx_exercise_not_deleted ON exercise (id) WHERE deleted_at IS NULL;

--;;
CREATE TABLE exercise_session (
    id UUID PRIMARY KEY,
    start_timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    end_timestamp TIMESTAMPTZ,
    notes TEXT,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    time_zone TEXT,
    user_id UUID
);
--;;
CREATE INDEX idx_exercise_session_not_deleted ON exercise_session (id) WHERE deleted_at IS NULL;

--;;
CREATE TABLE exercise_log (
    id UUID PRIMARY KEY,
    start_timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    end_timestamp TIMESTAMPTZ,
    exercise_session_id UUID NOT NULL,
    notes TEXT NOT NULL,
    interval_global_median_ending BOOLEAN NOT NULL,
    airtable_ported BOOLEAN NOT NULL,
    airtable_missing_duration NUMERIC NOT NULL,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    user_id UUID,
    CONSTRAINT fk_exercise_session
      FOREIGN KEY (exercise_session_id) REFERENCES exercise_session (id)
);
--;;
CREATE INDEX idx_exercise_log_not_deleted ON exercise_log (id) WHERE deleted_at IS NULL;

--;;
CREATE TABLE exercise_set (
    id UUID PRIMARY KEY,
    start_timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    end_timestamp TIMESTAMPTZ,
    exercise_id UUID NOT NULL,
    exercise_log_id UUID NOT NULL,
    distance NUMERIC(8,3),
    distance_unit TEXT CHECK (distance_unit IN ('miles', 'feet', 'yards', 'meters', 'kilometers')),
    weight_amount NUMERIC(6,2),
    weight_unit TEXT CHECK (weight_unit IN ('pounds', 'kilograms')),
    reps NUMERIC(5,1),
    interval_global_median_end BOOLEAN,
    airtable_ported BOOLEAN,
    airtable_exercise_id TEXT,
    airtable_missing_duration NUMERIC,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    user_id UUID,
    CONSTRAINT fk_exercise
      FOREIGN KEY (exercise_id) REFERENCES exercise (id),
    CONSTRAINT fk_exercise_log
      FOREIGN KEY (exercise_log_id) REFERENCES exercise_log (id)
);
--;;
CREATE INDEX idx_exercise_set_not_deleted ON exercise_set (id) WHERE deleted_at IS NULL;
