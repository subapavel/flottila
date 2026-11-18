-- The lines a step's code logged while the step ran, kept with the step's row: {"lines": [{"at", "level", "logger", "text",
-- "thrown"?}], "dropped": n}. Null when the step logged nothing; an earlier row has none.
alter table messages add column log jsonb;
