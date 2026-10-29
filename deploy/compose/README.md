# The gate stack (`deploy/compose`)

A demo stack for GATE checks 1-2 (D-085), not production: the node runs `FLOTTILA_ENV=dev`. Services: `postgres`, `valkey`
(persistence off), `s3` (SeaweedFS), `kafka` (KRaft, not wired), `sftp`, `flottila` (the node), `echo` (the demo's partner),
`deploy` (one-shot: `flottila deploy /packages/demo`), and `clickhouse` under the `history` profile (not wired).

```sh
mvn -B -ntp -q package -DskipTests -pl flottila-runtime,flottila-cli -am   # the jars the node image and `deploy` use
cd deploy/compose
export FLOTTILA_ADMIN_TOKEN=$(openssl rand -hex 16)    # required: compose refuses to start without it
export FLOTTILA_S3_SECRET_KEY=$(openssl rand -hex 16)  # required too; FLOTTILA_S3_ACCESS_KEY defaults to flottila
docker compose up -d --build                           # returns once the node is healthy
docker compose ps -a deploy                            # exited (0) after the demo is deployed
curl -H "Authorization: Bearer $FLOTTILA_ADMIN_TOKEN" localhost:8081/admin/packages
curl -X POST --data-binary @../../packages/demo/fixtures/sample-invoice.xml localhost:8080/in
docker compose --profile history up -d clickhouse      # optional
docker compose --profile history down -v               # stop and remove every volume
```

- Admin API and `/health`: `localhost:8081`; the demo's http-in: `localhost:8080`.
- Postgres password: `FLOTTILA_PG_PASSWORD` (default `flottila`). S3: bucket `flottila`, one Admin identity written at start
  from `FLOTTILA_S3_ACCESS_KEY` / `FLOTTILA_S3_SECRET_KEY` (an http endpoint, allowed in `dev`).
- SFTP: user `demo`, password `FLOTTILA_SFTP_PASSWORD` (default `demo`), directory `inbox`. The host key is generated into the
  `sftp-hostkeys` volume on first start; nothing is committed. Its fingerprint (P2-06):

  ```sh
  docker compose exec sftp ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
  ```
