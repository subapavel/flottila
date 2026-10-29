-- P2-02D (D-084): ingress dedupe by a consumer-supplied key. One row per (scope, key_hash): the qualified flow id and the
-- SHA-256 of the dedupe key's UTF-8 bytes (32 bytes; a key of any length indexes so), naming the first message saved under it
-- and when its ingress started (the node's injected clock). The row is inserted in the transaction of that message's durable
-- save (its body, ingress row and work item), so it exists exactly when the message does; a later arrival with the key is that
-- message. No foreign key: the retention job (P2-07) deletes a row only with or after its message's rows.
create table processed_ids (scope text not null,
                            key_hash bytea not null check (length(key_hash) = 32),
                            message_id text not null,
                            created_at timestamptz not null,
                            primary key (scope, key_hash));
