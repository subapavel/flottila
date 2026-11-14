-- P2-16a (D-121 A2, A3): the local hand-off. A saved item is written leased to its node (locked_by, locked_until, attempts 1)
-- when it heads its key: its insert asks whether any other row of (flow, partition_key) exists, under the key's transaction
-- advisory lock. This index answers that question, and the claim's oldest-of-key and live-lease-on-key probes, without a scan
-- of the flow's rows; unkeyed rows (the most) are not in it.
create index work_flow_key_seq on work (flow, partition_key, seq) where partition_key is not null;
