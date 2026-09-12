# Flottila — prototype (milestone 1)

`HTTP → validate → extractKey → route → XSLT → HTTP` with a snapshot after every stage, retry + DLQ, replay from any stage.
In-memory stores only; `MessageStore` / `MonitoringStore` are the seams where Postgres/ClickHouse go.

```
mvn -q compile exec:java
curl -s -X POST --data-binary @sample-invoice.xml http://localhost:8080/in -i        # → <ack>, header x-flottila-id
curl -s http://localhost:8080/monitor                                                # all messages: keys, props
curl -s "http://localhost:8080/monitor?key=invoiceId&value=4711"                     # search by business key
curl -s http://localhost:8080/monitor/<id>                                           # timeline: keys.invoiceId=4711, props.PARTNER
curl -s http://localhost:8080/monitor/<id>/payload/4                                 # payload as it was after route (before XSLT)
curl -s -X POST http://localhost:8080/monitor/<id>/replay/4                          # re-run from XSLT onwards
curl -s -X POST -d '<Invoice><Currency>CZK</Currency></Invoice>' http://localhost:8080/in   # 400, parked in DLQ
curl -s http://localhost:8080/dlq
```

Step numbering: stage *k* consumes an envelope at step *k* and produces step *k+1*.
Stage 0 = ingress (identity), so `payload/1` is exactly what arrived.
Stages: 0 ingress, 1 validate, 2 extractKey, 3 route (sets `props.PARTNER` — CZK → domestic, else foreign),
4 toPartner (XSLT), 5 partner (HTTP to the target chosen by `props.PARTNER`).

Layout mirrors the planned modules: `core` (no Vert.x beyond `Buffer`), `runtime`, `components`, `demo`.
