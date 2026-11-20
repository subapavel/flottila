# The gate stack (`deploy/compose`)

A demo stack for GATE checks 1-2 (D-085), not production: the node runs `RAIL8_ENV=dev`. Services: `postgres`, `valkey`
(persistence off), `sftp-keys` (one-shot: the SFTP host key), `sftp`, `netns` (holds the published ports and the network namespace of the node and the echo), `rail8` (the node), `echo` (the demo's partner),
`deploy` (one-shot: `rail8 deploy rail8 HEAD`), `clickhouse` under the `history` profile (not wired), and `kafka` (KRaft, the optional event-broker add-on) under the
`kafka` profile, off by default and not wired: a plain `up` does not start it.

```sh
./mvnw -B -ntp -q package -DskipTests -pl :rail8-node,:rail8-cli -am   # the jars the node image and `deploy` use, with the engines they need
cd deploy/compose
export RAIL8_ADMIN_TOKEN=$(openssl rand -hex 16)    # required: compose refuses to start without it
docker compose up -d --build                           # returns once the node is healthy
docker compose ps -a deploy                            # exited (0) after the demo is deployed
curl -H "Authorization: Bearer $RAIL8_ADMIN_TOKEN" localhost:8081/admin/packages
curl -X POST --data-binary @../../examples/demo/fixtures/sample-invoice.xml localhost:8080/http/in   # flow invoice-sync: the reply is the ACK
curl -X POST --data-binary @../../examples/demo/fixtures/sample-invoice.xml localhost:8080/http/invoice/async   # flow invoice-async: 202 after the durable save
docker compose --profile history up -d clickhouse      # optional
docker compose --profile kafka up -d kafka             # optional: the event-broker add-on
docker compose --profile history --profile kafka down -v   # stop and remove every volume
```

Postgres runs `postgres:18.6` with its volume at `/var/lib/postgresql` (D-129). A `pg-data` volume made by the earlier
`postgres:17.11` cannot be reused (the 18 image refuses to start on it): run `docker compose --profile history --profile kafka down -v` once
before the first `up` after the upgrade; it deletes every volume of this demo stack (database, the node's git cache and packages, SFTP keys and files, ClickHouse).

A stack that was started before the rename to Rail8 (2026-11-15, D-148) needs the same once, with `--remove-orphans` added, before the first
`up`: `docker compose --profile history --profile kafka down -v --remove-orphans`. Its `pg-data` volume holds the role and the database of
the former name and Postgres initialises only an empty volume, so the node cannot log in although `pg_isready` passes
(`docker compose logs postgres` shows the refused login). And the node's service was renamed, so the old node container is an orphan: a plain
`down -v` leaves it, and while it exists it keeps its volumes in use. The old node-data volume is no longer declared in this file and stays
after that: `docker volume ls` shows it under the former name, `docker volume rm` removes it.

- The package: the node reads this checkout as the git repository `rail8` (the repository's root mounted read-only at
  `/repos/rail8`, `RAIL8_GIT_BASE=file:///repos/`) and deploys its folder `examples/demo` (`RAIL8_GIT_PATH`). `deploy` deploys
  the checkout's `HEAD`: a change to the demo is deployed once it is committed, by `docker compose up deploy`. The checkout
  needs its full history and its own `.git` folder: a shallow clone or a `git worktree` of Rail8 cannot be deployed from.
- Admin API and `/health`: `localhost:8081`. The node's HTTP port, shared by every inbound HTTP endpoint: `localhost:8080`; the demo's http-in at `/http/in`, its async door `http-in-async` at `/http/invoice/async` (answers 202).
- Postgres password: `RAIL8_PG_PASSWORD` (default `rail8`).
- SFTP: user `demo`, password `RAIL8_SFTP_PASSWORD` (default `demo`), directory `inbox` (the demo's `sftp-inbox` polls `/inbox` every
  10 s and deletes a file once the flow `sftp-invoice` has saved it). The one-shot `sftp-keys` generates an RSA host key (3072 bits; the node's SFTP client does not offer ed25519) into the
  `sftp-hostkeys` volume on the first start (never replaced) and writes its public fingerprint to `host-key.fingerprint` in the separate volume
  `sftp-fingerprint`, the only one the node mounts (read-only, no host key reaches it); the
  node's entrypoint reads that file into `RAIL8_SECRET_SFTP_HOST_KEY`, the password reaches it as `RAIL8_SECRET_SFTP_PASSWORD`.
  Nothing secret or generated is in the repo. The fingerprint:

  ```sh
  docker compose exec sftp ssh-keygen -l -f /etc/ssh/ssh_host_rsa_key      # the server's own view; the node reads the same
  ```

  Drop a file into the inbox (it reaches the echo transformed; `docker compose logs echo`):

  ```sh
  docker compose cp ../../examples/demo/fixtures/inbox/invoice-4711.xml sftp:/home/demo/inbox/
  # or over SFTP from the sftp container: docker compose exec sftp sftp demo@localhost (password above), then: put <file> inbox
  ```
