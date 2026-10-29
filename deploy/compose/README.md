# The gate stack (`deploy/compose`)

A demo stack for GATE checks 1-2 (D-085), not production: the node runs `FLOTTILA_ENV=dev`. Services: `postgres`, `valkey`
(persistence off), `s3` (SeaweedFS), `kafka` (KRaft, not wired), `sftp-keys` (one-shot: the SFTP host key), `sftp`, `flottila` (the node), `echo` (the demo's partner),
`deploy` (one-shot: `flottila deploy /packages/demo`), and `clickhouse` under the `history` profile (not wired).

```sh
mvn -B -ntp -q package -DskipTests -pl flottila-runtime,flottila-cli -am   # the jars the node image and `deploy` use
cd deploy/compose
export FLOTTILA_ADMIN_TOKEN=$(openssl rand -hex 16)    # required: compose refuses to start without it
export FLOTTILA_S3_SECRET_KEY=$(openssl rand -hex 16)  # required too; FLOTTILA_S3_ACCESS_KEY defaults to flottila
docker compose up -d --build                           # returns once the node is healthy
docker compose ps -a deploy                            # exited (0) after the demo is deployed
curl -H "Authorization: Bearer $FLOTTILA_ADMIN_TOKEN" localhost:8081/admin/packages
curl -X POST --data-binary @../../packages/demo/fixtures/sample-invoice.xml localhost:8080/in        # flow invoice-sync: the reply is the ACK
curl -X POST --data-binary @../../packages/demo/fixtures/sample-invoice.xml localhost:8082/invoice/async   # flow invoice-async: 202 after the durable save
docker compose --profile history up -d clickhouse      # optional
docker compose --profile history down -v               # stop and remove every volume
```

- Admin API and `/health`: `localhost:8081`; the demo's http-in: `localhost:8080`; its async door `http-in-async`: `localhost:8082/invoice/async` (answers 202).
- Postgres password: `FLOTTILA_PG_PASSWORD` (default `flottila`). S3: bucket `flottila`, one Admin identity written at start
  from `FLOTTILA_S3_ACCESS_KEY` / `FLOTTILA_S3_SECRET_KEY` (an http endpoint, allowed in `dev`).
- SFTP: user `demo`, password `FLOTTILA_SFTP_PASSWORD` (default `demo`), directory `inbox` (the demo's `sftp-inbox` polls `/inbox` every
  10 s and deletes a file once the flow `sftp-invoice` has saved it). The one-shot `sftp-keys` generates an RSA host key (3072 bits; the node's SFTP client does not offer ed25519) into the
  `sftp-hostkeys` volume on the first start (never replaced) and writes its public fingerprint to `host-key.fingerprint` in the separate volume
  `sftp-fingerprint`, the only one the node mounts (read-only, no host key reaches it); the
  node's entrypoint reads that file into `FLOTTILA_SECRET_SFTP_HOST_KEY`, the password reaches it as `FLOTTILA_SECRET_SFTP_PASSWORD`.
  Nothing secret or generated is in the repo. The fingerprint:

  ```sh
  docker compose exec sftp ssh-keygen -l -f /etc/ssh/ssh_host_rsa_key      # the server's own view; the node reads the same
  ```

  Drop a file into the inbox (it reaches the echo transformed; `docker compose logs echo`):

  ```sh
  docker compose cp ../../packages/demo/fixtures/inbox/invoice-4711.xml sftp:/home/demo/inbox/
  # or over SFTP from the sftp container: docker compose exec sftp sftp demo@localhost (password above), then: put <file> inbox
  ```
