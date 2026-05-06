ALTER TABLE notification_preferences
ADD COLUMN IF NOT EXISTS quiet_hours_enabled BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE notification_preferences
ADD COLUMN IF NOT EXISTS quiet_hours_start_minute INTEGER NOT NULL DEFAULT 1320;

ALTER TABLE notification_preferences
ADD COLUMN IF NOT EXISTS quiet_hours_end_minute INTEGER NOT NULL DEFAULT 480;

ALTER TABLE notification_preferences
ADD COLUMN IF NOT EXISTS quiet_hours_timezone TEXT NOT NULL DEFAULT 'UTC';

ALTER TABLE notification_preferences
DROP CONSTRAINT IF EXISTS notification_preferences_quiet_hours_start_minute_check;

ALTER TABLE notification_preferences
ADD CONSTRAINT notification_preferences_quiet_hours_start_minute_check
CHECK (quiet_hours_start_minute BETWEEN 0 AND 1439);

ALTER TABLE notification_preferences
DROP CONSTRAINT IF EXISTS notification_preferences_quiet_hours_end_minute_check;

ALTER TABLE notification_preferences
ADD CONSTRAINT notification_preferences_quiet_hours_end_minute_check
CHECK (quiet_hours_end_minute BETWEEN 0 AND 1439);

ALTER TABLE notification_preferences
DROP CONSTRAINT IF EXISTS notification_preferences_quiet_hours_timezone_check;

ALTER TABLE notification_preferences
ADD CONSTRAINT notification_preferences_quiet_hours_timezone_check
CHECK (char_length(quiet_hours_timezone) BETWEEN 1 AND 64);
