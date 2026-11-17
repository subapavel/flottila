-- An operator's cancel of an at-least-once message that has not ended marks its work row with the cancel's audit row. A marked
-- row is handed out as any row is: a run that starts on it stops before its first step does anything, and a run already going
-- on it stops at its holder's next lease renewal; the row is then completed. A marked row is never held or stopped, and a
-- deferred one is due at once. Null: not marked (every row before V21).
alter table work add column cancel_audit bigint;
