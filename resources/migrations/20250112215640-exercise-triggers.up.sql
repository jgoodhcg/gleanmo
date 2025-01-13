-- Create trigger to automatically set session end on each log insertion
CREATE OR REPLACE FUNCTION update_session_end_timestamp()
RETURNS TRIGGER AS $$
BEGIN
  UPDATE exercise_session
  SET end_timestamp = (
    SELECT MAX(end_timestamp)
    FROM exercise_log
    WHERE exercise_log.exercise_session_id = NEW.exercise_session_id
  )
  WHERE id = NEW.exercise_session_id;

  RETURN NEW;
END;
$$ LANGUAGE plpgsql;
--;;

CREATE TRIGGER trg_update_session_end
AFTER INSERT OR UPDATE ON exercise_log
FOR EACH ROW
EXECUTE FUNCTION update_session_end_timestamp();
--;;

-- Create trigger to automatically set log end on each set insertion
CREATE OR REPLACE FUNCTION update_log_end_timestamp()
RETURNS TRIGGER AS $$
BEGIN
  UPDATE exercise_log
  SET end_timestamp = (
    SELECT MAX(end_timestamp)
    FROM exercise_set
    WHERE exercise_set.exercise_log_id = NEW.exercise_log_id
  )
  WHERE id = NEW.exercise_log_id;

  RETURN NEW;
END;
$$ LANGUAGE plpgsql;
--;;

CREATE TRIGGER trg_update_log_end
AFTER INSERT OR UPDATE ON exercise_set
FOR EACH ROW
EXECUTE FUNCTION update_log_end_timestamp();
