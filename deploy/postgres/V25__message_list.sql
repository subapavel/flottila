-- The list of recent messages: a flow's or every flow's messages by the start of their arrival, newest first
-- (GET /admin/messages?from=&to=). One entry per message: the arrival row (step 0) of its first attempt. The index serves
-- `where step = 0 and attempt = 1 and started_at in the range order by started_at desc, id desc`, read backwards.
create index messages_arrivals on messages (started_at, id) where step = 0 and attempt = 1;
-- Whether an operator cancelled a message: its audit row by subject (`message/<id>`). Operators' acts are few, so the index
-- costs little.
create index audit_message_cancel on audit (subject) where action = 'message.cancel';
