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
mvn -B -ntp -q -f platform/pom.xml package -DskipTests -pl :rail8-node -am      # the node's jar, which the image copies
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
```

```sh
mkdir -p ~/rail8-workspace && cp -r examples/demo ~/rail8-workspace/demo   # once: the packages the endpoint forms edit
cd ui && QUARKUS_HTTP_PORT=18080 RAIL8_WORKSPACE=$HOME/rail8-workspace npm run dev   # port 18080: the demo's http-in already uses 8080
```

Open **http://localhost:5173**. The UI has **Messages**, the list of the messages the node recorded, **Endpoints**, the
settings form of each endpoint file in `RAIL8_WORKSPACE`, **Flows**, the picture of each flow file in it, and
**Mappings**, the mapping studio. The UI sends its
`/admin` requests to the node on `localhost:8081` and adds the token itself, so the browser never sees it.

An endpoint's form is drawn from its component's settings description, which the node serves, so it needs the node
running. It opens read-only; Edit, change a setting, Check, and Save writes the file in the working folder, changing only
the lines of the settings you changed. Deploy the edited package with `deploy/quickstart/deploy.sh ~/rail8-workspace/demo`.
With `RAIL8_READ_ONLY=true` the forms only show the files, as on a server.

To see the demo flows as pictures, open **Flows** and choose a flow, such as `invoice-sync`. Its tab draws the flow's
steps from top to bottom: where messages arrive, each step, the fork of a `to` by prop into its endpoints, a call of
another flow beside the step that makes it, and the end. Choose a node to see its settings in the drawer beside the
picture; with none chosen, the drawer shows the flow's own settings. An endpoint opens in its form with Open endpoint,
a flow called with Open flow. The picture only shows the file for now; the settings of the steps of modules and plugins
come from the node, so it needs the node running too.

The node migrates its database itself and serves `/health` on `localhost:8081`. Postgres is published on the host's
loopback port 5432; if that is taken, prefix the `up` with `RAIL8_PG_HOST_PORT=55432`. Without a token the node exits
with "the admin API on 0.0.0.0 is not loopback-only: it needs a token" (`docker compose logs rail8`).

## Every morning

Stop the mapping service and the UI (Ctrl+C), then:

```sh
git pull
bin/install-jq && bin/install-mapping
mvn -B -ntp -q -f platform/pom.xml package -DskipTests -pl :rail8-node -am
./mvnw -B -ntp -q -f mapping/pom.xml -pl service -am package -DskipTests
(cd ui && npm ci)
export RAIL8_ADMIN_TOKEN=<the token of the first run>   # a new shell has none, and the node refuses to start without it
docker compose up -d --build --force-recreate           # the node restores the deployed demo from its volume
deploy/quickstart/deploy.sh examples/demo               # only when the demo itself changed
```

Then start the mapping service and the UI again with the commands of the first run (not the copies, which are made
once), with the same token exported in the UI's terminal.

If the node does not start after a pull, the database volume may be from an older checkout. `docker compose down -v`
removes this stack's database and archives; start again from `docker compose up`, then deploy the demo again.

## Seeing it work

```sh
curl -X POST --data-binary @examples/demo/fixtures/sample-invoice.xml -H 'Content-Type: application/xml' localhost:8080/in
```

The flow validates the invoice, keys it by `invoiceId`, routes it by currency, maps it with XSLT and sends it to the
partner; the partner's answer is the reply, the invoice as mapped: `<PartnerInvoice number="4711" currency="CZK">…`.

In the UI open **Messages**. It opens on the messages of the last hour, newest first: the invoice is the top row, with
its start, the flow `demo/invoice-sync`, the status Completed, its duration and its business key `invoiceId=4711`.
Above the list, the time range (the last 15 minutes, hour, 24 hours or 7 days), the flow and the status narrow it as soon
as they are chosen; a key name and value, or the name of a step the message passed, narrow it with **Search**, which
also reads the list again up to now. Just above the list, a strip counts the messages of the time range and flow by
status: the invoice is counted under Completed, and Failed shows 0. Choosing a count shows only the messages in that status;
choosing it again shows them all. Type `invoiceId` and `4711` and Search: only the invoice is left. Open it by its
id: its timeline lists the steps `ingress`, `validate`, `extractKey`, `route`, `toPartner` and `partner`, and each step
shows the payload it recorded. The same through the admin API:

```sh
auth="Authorization: Bearer $RAIL8_ADMIN_TOKEN"
from=$(date -u -d '1 hour ago' +%FT%TZ 2>/dev/null || date -u -v-1H +%FT%TZ)   # an hour ago, in UTC (GNU date, or macOS date)
curl -H "$auth" "localhost:8081/admin/messages?from=$from&to=$(date -u +%FT%TZ)"
# {"items":[{"id":"<id>","flow":"demo/invoice-sync","status":"completed","startedAt":"…","durationNanos":…,"keys":{"invoiceId":"4711"}}],"next":null}
curl -H "$auth" "localhost:8081/admin/messages?from=$from&to=$(date -u +%FT%TZ)&status=failed"   # the ones that failed: none
curl -H "$auth" "localhost:8081/admin/message-counts?from=$from&to=$(date -u +%FT%TZ)"   # {"completed":1,"failed":0,"running":0,"cancelled":0}: by status
curl -H "$auth" 'localhost:8081/admin/messages?key=invoiceId:4711'    # the ids of the messages with that key, at any time: {"items":["<id>"],"next":null}
curl -H "$auth" localhost:8081/admin/messages/<id>/steps/5/body       # the payload after step 5: the partner's answer
```

The list's statuses are completed, failed (parked after its retries, or a step of its latest run failed), running (an
at-least-once message queued, waiting for its retry or running) and cancelled (ended by an operator). A message is
listed by the start of its arrival, so a flow whose recording level is `errors` or `off` lists none.

A foreign invoice takes the other route: `sed -e 's/4711/4712/' -e 's/CZK/EUR/' examples/demo/fixtures/sample-invoice.xml | curl -X POST --data-binary @- -H 'Content-Type: application/xml' localhost:8080/in`.

The `demo/order-jq` flow turns a JSON order with a jq filter: it keeps the order's id and customer as `orderId` and
`customer`, adds up the items into `total`, and calls the flow `demo/order-partner` with the result, which sends it to the
partner.

```sh
curl -X POST --data-binary @examples/demo/fixtures/sample-order.json -H 'Content-Type: application/json' localhost:8084/order
```

The reply is the partner's echo of what it was sent: `{"orderId":42,"customer":"Acme Corp","total":50}`. In the UI's
**Messages** the order is the top row of the flow `demo/order-partner`, the flow it called, and the row below it is the
order of `demo/order-jq`. Choose the flow `demo/order-jq`, or type the step name `pick` and Search, and open the message:
its timeline lists the steps `ingress`, `pick` and `partner`, and the payload `pick` recorded is that JSON. The same
through the admin API: `curl -H "$auth" 'localhost:8081/admin/messages?flow=demo/order-jq&step=pick'` gives its `<id>`.

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
