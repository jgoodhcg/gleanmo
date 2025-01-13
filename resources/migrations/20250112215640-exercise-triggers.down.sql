DROP TRIGGER IF EXISTS trg_update_session_end ON exercise_log;
--;;
DROP FUNCTION IF EXISTS update_session_end_timestamp;

--;;
DROP TRIGGER IF EXISTS trg_update_log_end ON exercise_set;
--;;
DROP FUNCTION IF EXISTS update_log_end_timestamp;
