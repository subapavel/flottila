# Quickstart: run Rail8 and its UI on your machine

One node with its Postgres and a partner echo in Docker, and the UI in your browser, all from one terminal. The demo
package (`examples/demo`) is deployed to the node, an invoice is sent in and found in the UI. Commands run from the
repository root.

## Prerequisites

| Tool | Version | Install |
| --- | --- | --- |
| JDK | 25 | any distribution, e.g. Temurin |
| Maven | none | the repository's `./mvnw` wrapper fetches the Maven it needs (3.9.11) and builds everything |
| Node.js | 22.12 or newer | with npm |
| Docker | Compose v2 | Docker Engine on Linux, Docker Desktop on macOS and Windows |
| `curl`, `zip` | | `zip` is optional: `deploy.sh` falls back to the JDK's `jar` |

The scripts are shell scripts. On Windows run everything inside WSL 2, with Docker Desktop's WSL integration on and the
repository cloned inside the WSL file system.

## First run

```sh
git clone https://github.com/subapavel/rail8.git && cd rail8
./mvnw -B -ntp -q package -DskipTests -pl :rail8-node -am   # the node's jar, which the image copies
(cd ui && npm ci)

export RAIL8_ADMIN_TOKEN=rail8-local               # the admin API's bearer token; any string will do, keep it for later shells
docker compose up -d --build                       # the node, its Postgres and the partner echo
until curl -sf localhost:8081/health >/dev/null; do sleep 2; done
deploy/quickstart/deploy.sh examples/demo          # the demo flows, deployed to the node
```

Then start the UI in the same terminal, where `RAIL8_ADMIN_TOKEN` is exported; it runs until Ctrl+C:

```sh
mkdir -p ~/rail8-workspace && cp -r examples/demo ~/rail8-workspace/demo   # once: the packages the endpoint forms edit
cp -r mapping/corpus ~/rail8-workspace/mapping-corpus                     # once: the mappings the mapping studio edits
cd ui && RAIL8_WORKSPACE=$HOME/rail8-workspace npm run dev
```

Open **http://localhost:5173**. The UI has **Messages**, the list of the messages the node recorded, **Endpoints**, the
settings form of each endpoint file in `RAIL8_WORKSPACE`, **Flows**, the picture of each flow file in it, and
**Mappings**, the mapping studio. The UI sends its
`/admin` requests to the node on `localhost:8081` and adds the token itself, so the browser never sees it.

The studio's mappings are the `*.mapping.json` files in `RAIL8_WORKSPACE`, such as
`mapping-corpus/mappings/order-id-copy`, and the schemas a mapping reads are the files its bundles name, relative to its
file. **Mappings** lists them without the node; opening, saving, compiling and testing a mapping ask the node, so they
need it running. With `RAIL8_READ_ONLY=true` the studio saves and creates nothing.

An endpoint's form is drawn from its component's settings description, which the node serves, so it needs the node
running. It opens read-only; Edit, change a setting, Check, and Save writes the file in the working folder, changing only
the lines of the settings you changed. Deploy the edited package with `deploy/quickstart/deploy.sh ~/rail8-workspace/demo`.
With `RAIL8_READ_ONLY=true` the forms only show the files, as on a server.

To see the demo flows as pictures, open **Flows** and choose a flow, such as `invoice-sync`. Its tab draws the flow's
steps from top to bottom: where messages arrive, each step, the fork of a `to` by prop into its endpoints, a call of
another flow beside the step that makes it, and the end. Choose a node to see its settings in the drawer beside the
picture; with none chosen, the drawer shows the flow's own settings. An endpoint opens in its form with Open endpoint,
a flow called with Open flow. The settings of the steps of modules and plugins come from the node, so it needs the node
running too.

To edit a flow, open `invoice-sync` and choose Edit. The + on an edge adds a step there: choose its type, such as
`filter`, and fill in its settings in the drawer. Choose a step to move it up or down or delete it with the drawer's
buttons, or, with the canvas focused, with the arrow keys and Delete; Undo (or Ctrl+Z) takes a change back. The
problems are found again after every change; Check lists them, and they do not stop Save. Save writes
`~/rail8-workspace/demo/flows/invoice-sync.yaml`, changing only the lines of what you changed and keeping its comments.
Deploy it with `deploy/quickstart/deploy.sh ~/rail8-workspace/demo`. A flow written in a form the editor does not keep,
such as its steps on one line, opens read-only and says why.

While a flow is edited, the rules of a `route` and the endpoints of a `to` by prop are tables: Add a row, change a row's
values in place, move a rule up or down, remove a row, and rename an endpoint's value in its first column. With no node
chosen, the drawer is a form of the flow's own settings, such as its `delivery.mode`, and Add a process adds a process
with its first step as a lane of its own. Choose a lane to rename its process, which renames every call of it too, or to
delete it once no step calls it; the drawer of a step moves it to the end of another lane with Move to. The + on an edge
also offers Call a flow: choose `order-partner`, and the step sends to `order-partner-call`, the `direct` endpoint whose
address that flow serves. A flow whose address no endpoint of the package names gets one, `call-<flow id>.yaml`, written
before the step is added; Cancel leaves it in place. New flow, on the Flows screen, writes a new flow file with one step,
such as `demo/flows/audit.yaml` arriving at `http-in` and sending to `partner-domestic`, and opens it; it never writes
over an existing file.

The node migrates its database itself and serves `/health` on `localhost:8081`. Postgres is published on the host's
loopback port 5432; if that is taken, prefix the `up` with `RAIL8_PG_HOST_PORT=55432`. Without a token the node exits
with "the admin API on 0.0.0.0 is not loopback-only: it needs a token" (`docker compose logs rail8`).

## Every morning

Stop the UI (Ctrl+C) and go back to the repository root (`cd ..`), then:

```sh
git pull
./mvnw -B -ntp -q package -DskipTests -pl :rail8-node -am
(cd ui && npm ci)
export RAIL8_ADMIN_TOKEN=<the token of the first run>   # a new shell has none, and the node refuses to start without it
docker compose up -d --build --force-recreate           # the node restores the deployed demo from its volume
deploy/quickstart/deploy.sh examples/demo               # only when the demo itself changed
```

A working copy of the mapping corpus made by an older checkout, `~/rail8-mapping-workspace`, is moved into the working
folder once, which keeps its edits: `mv ~/rail8-mapping-workspace ~/rail8-workspace/mapping-corpus`. The studio then
finds its mappings under `mapping-corpus/`, and nothing else reads the old folder.

Then start the UI again in the same terminal, with the last command of the first run (not the copies, which are made
once): `cd ui && RAIL8_WORKSPACE=$HOME/rail8-workspace npm run dev`.

If the node does not start after a pull, the database volume may be from an older checkout. `docker compose down -v`
removes this stack's database and archives; start again from `docker compose up`, then deploy the demo again.

A package deployed by an older checkout can be listed FAILED after a pull when its endpoint files name an option the node
no longer takes. For example, an `http` in endpoint now names its `address` (`/in`, served at `localhost:8080/http/in`),
no longer a `host`, `port` or `path`. The node's restore keeps the package as it was deployed, so deploy it again from
the checkout with `deploy/quickstart/deploy.sh examples/demo`. The UI's copy keeps the old files too: copy the changed
ones over it, such as `cp examples/demo/endpoints/http-in*.yaml ~/rail8-workspace/demo/endpoints/`. This drops any edits
of your own in those files.

## Seeing it work

```sh
curl -X POST --data-binary @examples/demo/fixtures/sample-invoice.xml -H 'Content-Type: application/xml' localhost:8080/http/in
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

A foreign invoice takes the other route: `sed -e 's/4711/4712/' -e 's/CZK/EUR/' examples/demo/fixtures/sample-invoice.xml | curl -X POST --data-binary @- -H 'Content-Type: application/xml' localhost:8080/http/in`.

The flow `demo/invoice-async` takes the same invoice at least once: it answers `202` after saving it, and a worker sends it on.

```sh
curl -i -X POST --data-binary @examples/demo/fixtures/sample-invoice.xml -H 'Content-Type: application/xml' localhost:8080/http/invoice/async
```

In **Messages** choose the flow `demo/invoice-async`: the invoice is its top row, Completed once the worker has sent it to the
partner. Its timeline lists `ingress`, `validate`, `extractKey`, `route`, `toPartner` and `partner`.

The flow `demo/sftp-invoice` takes a file dropped in the inbox of the stack's SFTP server (user `demo`, password `demo`). Drop one:

```sh
docker compose cp examples/demo/fixtures/inbox/invoice-4711.xml sftp:/home/demo/inbox/
```

The node polls the inbox every 10 seconds, so within about ten seconds the file is gone from the inbox
(`docker compose exec sftp ls /home/demo/inbox` lists nothing) and **Messages**, with the flow `demo/sftp-invoice` chosen, shows
the invoice as its top row, Completed, with its steps `ingress`, `validate`, `extractKey`, `toPartner` and `partner`. The
host key of the SFTP server is generated when the stack first starts and the node pins its fingerprint; the password is the
demo's, or `RAIL8_SFTP_PASSWORD` when it is exported before the first `up`.

The `demo/order-jq` flow turns a JSON order with a jq filter: it keeps the order's id and customer as `orderId` and
`customer`, adds up the items into `total`, and calls the flow `demo/order-partner` with the result, which sends it to the
partner.

```sh
curl -X POST --data-binary @examples/demo/fixtures/sample-order.json -H 'Content-Type: application/json' localhost:8080/http/order
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

Ctrl+C stops the UI.

## Notes

`deploy/quickstart/deploy.sh` zips the directory, PUTs it to `/admin/packages/demo` with the token and prints the node's
answer, or exits non-zero on a refusal; `RAIL8_ADMIN` (default `http://localhost:8081`) points it at another node.

The `echo` service is the demo's partner: `deploy/quickstart/PartnerEcho.java` on `localhost:8090`, answering every path
with the body it was sent. The `sftp` service is the SFTP server of `demo/sftp-invoice`; its `sftp-keys` one-shot makes the
host key at the first start. The fuller stack, [`deploy/compose/`](../deploy/compose/README.md), also has Valkey,
SeaweedFS S3 and, with `--profile kafka`, Kafka.
