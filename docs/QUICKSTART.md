# Quickstart: run Rail8 and its UI on your machine

One node with its Postgres and a partner echo in Docker, the mapping service, and the UI in your browser. The demo
package (`examples/demo`) is deployed to the node, an invoice is sent in and found in the UI. Commands run from the
repository root.

## Prerequisites

| Tool | Version | Install |
| --- | --- | --- |
| JDK | 25 | any distribution, e.g. Temurin |
| Maven | 3.9 | for the platform build; the mapping build uses the `./mvnw` wrapper in the repository |
| Node.js | 22.12 or newer | with npm |
| Docker | Compose v2 | Docker Engine on Linux, Docker Desktop on macOS and Windows |
| `curl`, `zip` | | `zip` is optional: `deploy.sh` falls back to the JDK's `jar` |

The scripts are shell scripts. On Windows run everything inside WSL 2, with Docker Desktop's WSL integration on and the
repository cloned inside the WSL file system.

## First run

```sh
git clone https://github.com/subapavel/rail8.git && cd rail8
bin/install-jq && bin/install-mapping                                           # the engines the jq and mapping steps depend on
mvn -B -ntp -q -f platform/pom.xml package -DskipTests -pl :rail8-runtime -am   # the node's jar, which the image copies
./mvnw -B -ntp -q -f mapping/pom.xml -pl service -am package -DskipTests        # the mapping service the studio talks to
(cd ui && npm ci)

export RAIL8_ADMIN_TOKEN=rail8-local               # the admin API's bearer token; any string will do, keep it for later shells
docker compose up -d --build                       # the node, its Postgres and the partner echo
until curl -sf localhost:8081/health >/dev/null; do sleep 2; done
deploy/quickstart/deploy.sh examples/demo          # the demo flows, deployed to the node
```

Then start the mapping service and the UI, each in its own terminal (the same `RAIL8_ADMIN_TOKEN` exported in the UI's):

```sh
cp -r mapping/corpus ~/rail8-mapping-workspace     # once: the mapping studio's working copy
java -Dquarkus.http.port=18080 -Drail8.mapping.workspace=$HOME/rail8-mapping-workspace -jar mapping/service/target/quarkus-app/quarkus-run.jar
cd ui && QUARKUS_HTTP_PORT=18080 npm run dev       # port 18080: the demo's http-in already uses 8080
```

Open **http://localhost:5173**. The UI has **Messages**, a search over what the node stored, and **Mappings**, the
mapping studio. The UI sends its `/admin` requests to the node on `localhost:8081` and adds the token itself, so the
browser never sees it.

The node migrates its database itself and serves `/health` on `localhost:8081`. Postgres is published on the host's
loopback port 5432; if that is taken, prefix the `up` with `RAIL8_PG_HOST_PORT=55432`. Without a token the node exits
with "the admin API on 0.0.0.0 is not loopback-only: it needs a token" (`docker compose logs rail8`).

## Every morning

Stop the mapping service and the UI (Ctrl+C), then:

```sh
git pull
bin/install-jq && bin/install-mapping
mvn -B -ntp -q -f platform/pom.xml package -DskipTests -pl :rail8-runtime -am
./mvnw -B -ntp -q -f mapping/pom.xml -pl service -am package -DskipTests
(cd ui && npm ci)
export RAIL8_ADMIN_TOKEN=<the token of the first run>   # a new shell has none, and the node refuses to start without it
docker compose up -d --build --force-recreate           # the node restores the deployed demo from its volume
deploy/quickstart/deploy.sh examples/demo               # only when the demo itself changed
```

Then start the mapping service and the UI again with the two commands of the first run, with the same token exported
in the UI's terminal.

If the node does not start after a pull, the database volume may be from an older checkout. `docker compose down -v`
removes this stack's database and archives; start again from `docker compose up`, then deploy the demo again.

## Seeing it work

```sh
curl -X POST --data-binary @examples/demo/fixtures/sample-invoice.xml -H 'Content-Type: application/xml' localhost:8080/in
```

The flow validates the invoice, keys it by `invoiceId`, routes it by currency, maps it with XSLT and sends it to the
partner; the partner's answer is the reply, the invoice as mapped: `<PartnerInvoice number="4711" currency="CZK">…`.

In the UI open **Messages**, search by key name `invoiceId` and key value `4711`, and open the message found: its
timeline lists the steps `ingress`, `validate`, `extractKey`, `route`, `toPartner` and `partner`, and each step shows
the payload it recorded. The same through the admin API:

```sh
auth="Authorization: Bearer $RAIL8_ADMIN_TOKEN"
curl -H "$auth" 'localhost:8081/admin/messages?key=invoiceId:4711'    # {"items":["<id>"],"next":null}
curl -H "$auth" localhost:8081/admin/messages/<id>/steps/5/body       # the payload after step 5: the partner's answer
```

A foreign invoice takes the other route: `sed -e 's/4711/4712/' -e 's/CZK/EUR/' examples/demo/fixtures/sample-invoice.xml | curl -X POST --data-binary @- -H 'Content-Type: application/xml' localhost:8080/in`.

## Stop

```sh
docker compose down      # keeps the database and the archives in their volumes
docker compose down -v   # also removes them: the next start is empty, deploy the demo again
```

Ctrl+C stops the mapping service and the UI.

## Notes

`deploy/quickstart/deploy.sh` zips the directory, PUTs it to `/admin/packages/demo` with the token and prints the node's
answer, or exits non-zero on a refusal; `RAIL8_ADMIN` (default `http://localhost:8081`) points it at another node.

The `echo` service is the demo's partner: `deploy/quickstart/PartnerEcho.java` on `localhost:8090`, answering every path
with the body it was sent. The demo's other two flows, `demo/invoice-async` and `demo/sftp-invoice`, need more than this
stack (no SFTP server here, its secrets are placeholders); they run in the fuller stack,
[`deploy/compose/`](../deploy/compose/README.md), which also has Valkey, SeaweedFS S3 and, with `--profile kafka`, Kafka.
