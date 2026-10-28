# Quickstart: send a message through Flottila on your machine

One node, its Postgres and a partner echo in Docker; the demo package (`packages/demo`) deployed to the node; an invoice
sent in and read back through the admin API. Commands run from the repository root.

**Prerequisites:** Docker with Compose v2, JDK 25, Maven 3.9, `curl`, and `zip` (else `deploy.sh` uses the JDK's `jar`).

## 1. Build and start

```sh
mvn -B -ntp -q package -DskipTests -pl flottila-runtime -am   # the jar and its target/lib, which the image copies
export FLOTTILA_ADMIN_TOKEN=$(openssl rand -hex 16)           # the admin API's bearer token; any string will do
docker compose up -d --build
until curl -sf localhost:8081/health >/dev/null; do sleep 2; done   # the node, not just postgres, is up
```

The node migrates the database itself (Flyway) and serves `/health` on `localhost:8081`. Compose also publishes Postgres on
the host's loopback, port 5432; if that port is taken, prefix the `up` with `FLOTTILA_PG_HOST_PORT=55432`. Without a token
the node exits with "the admin API on 0.0.0.0 is not loopback-only: it needs a token" (`docker compose logs flottila`).

The `echo` service is the demo's partner: `deploy/quickstart/PartnerEcho.java` in the node's network namespace, on
`localhost:8090`; it answers every path with the body it was sent.

## 2. Deploy the demo

```sh
deploy/quickstart/deploy.sh packages/demo
```

It zips the directory (`package.yaml` at the root), PUTs it to `/admin/packages/demo` with the token and prints the node's
answer — `{"package":"demo","version":"0.1.0",…,"flows":["demo/invoice-sync"],"added":["demo/invoice-sync"],…}` — or exits
non-zero on a refusal. `FLOTTILA_ADMIN` (default `http://localhost:8081`) points it at another node.

## 3. Send an invoice

```sh
curl -X POST --data-binary @packages/demo/fixtures/sample-invoice.xml -H 'Content-Type: application/xml' localhost:8080/in
```

The flow validates the invoice, keys it by `invoiceId`, routes it by currency, maps it with XSLT and sends it to the partner;
the partner's answer is the reply — the echo returns what it got, the invoice as mapped:
`<PartnerInvoice number="4711" currency="CZK"><Total>550.5</Total>…`.

## 4. Read it back

```sh
auth="Authorization: Bearer $FLOTTILA_ADMIN_TOKEN"
curl -H "$auth" 'localhost:8081/admin/messages?key=invoiceId:4711'    # {"items":["<id>"],"next":null}, or
                                                                       # "items":[] for a moment after the reply
curl -H "$auth" localhost:8081/admin/messages/<id>                    # the timeline
curl -H "$auth" localhost:8081/admin/messages/<id>/steps/5/body       # the payload after step 5
```

The timeline lists the steps of `demo/invoice-sync`: `ingress`, `validate`, `extractKey`, `route`, `toPartner` and
`partner`, each with its number, outcome, headers, props and body reference (hash, size). `steps/<n>/body` serves what step
n recorded: step 0 is the invoice as received, step 4 the mapped invoice, step 5 the partner's answer.

## 5. A foreign invoice

```sh
sed -e 's/4711/4712/' -e 's/CZK/EUR/' packages/demo/fixtures/sample-invoice.xml \
  | curl -X POST --data-binary @- -H 'Content-Type: application/xml' localhost:8080/in
docker compose logs echo        # POST /echo for 4711, POST /echo-foreign for 4712
```

## 6. Stop and clean

```sh
docker compose down             # stop and remove the containers; the database and the archives stay in their volumes
docker compose down -v          # also remove the volumes: the next start is empty, deploy the demo again
```

After a rebuild of the jar, `docker compose up -d --build --force-recreate` replaces all three containers (the echo has to
join the new node's network namespace); the node restores the deployed demo from its volume.
