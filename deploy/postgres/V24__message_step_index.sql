-- The search by step: the ids of a flow's messages that passed a named step, newest first (GET /admin/messages?flow=&step=).
-- The index serves `where flow and step_name order by started_at desc, id desc` and the cursor's `(started_at, id) <`, read
-- backwards; the check that no later attempt of the message has a row of the same step name goes by the primary key.
-- Every recorded row adds one entry. A plain `create index`: writes to `messages` wait while it builds over the rows retention
-- keeps (7 days by default).
create index messages_flow_step on messages (flow, step_name, started_at, id);
