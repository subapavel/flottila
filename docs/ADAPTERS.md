# Writing an adapter

An adapter connects Rail8 to one kind of outside system: it is a component of its own scheme, with a consumer that takes
messages in, a producer that sends them out, and a settings description from which the node checks endpoint files and the
UI draws their forms. It is built with the development kit:

- **the contract**, `rail8-api`: the `Plugin` and `Component` interfaces and the settings types;
- **the test suite**, `rail8-testkit`: the contract suites `ComponentContract` and `PollingConsumerContract`, the fakes, and
  `RecordingComponent`, the mock of any scheme;
- **the sample**, `examples/webhook-plugin`: an adapter of the scheme `webhook`, an HTTP consumer and producer, and a package
  that uses it;
- **the settings description**, `Component.settings()`: every option an endpoint of the adapter may set;
- **the template**, `bin/rail8 new-adapter`: the skeleton of a new adapter;
- **the local run**, `bin/run-adapter`: a node with the adapter and the UI on your machine.

What a component promises the node is in `docs/ARCHITECTURE.md`, part 5, "Writing an adapter plugin". Commands run from the
root of a Rail8 checkout.

## Prerequisites

A JDK 25, Maven 3.9 for the adapter's own build (the checkout builds itself with its `./mvnw`), Node.js 22.12 or newer
with npm, Docker, and `curl`. On Windows run everything inside WSL 2.

Build the kit once, and again after a `git pull`:

```sh
./mvnw -B -ntp -q install -DskipTests -pl :rail8-cli -am   # the contract and test suite in ~/.m2, the node's and the cli's jars
(cd ui && npm ci)                                           # the UI
```

## Create an adapter

```sh
bin/rail8 new-adapter ~/acme-adapter --scheme acme --package com.acme.rail8
```

The folder must not exist or be empty. The scheme is a lower-case letter followed by lower-case letters and digits, and no
scheme the node already has (`http`, `sftp`, `mock`); the package defaults to `com.example.<scheme>`. The skeleton is the
sample under the new names:

| File | What it is |
| --- | --- |
| `pom.xml` | the build: `mvn package` writes the adapter's jar into `target/plugin` |
| `src/main/java/…/AcmeComponent.java` | the component: its settings description, consumer, producer and health |
| `src/main/java/…/AcmePlugin.java`, `src/main/resources/META-INF/services/…` | the plugin that adds the component, and the file that names it |
| `src/test/java/…/AcmeComponentContractTest.java` | the component against the contract suite |
| `src/test/java/…/AcmeMockContractTest.java` | the adapter's mock against the same suite |
| `src/test/java/…/AcmeComponentTest.java` | what the suite leaves to the component: its own answers and refusals |
| `package/` | a package that uses the adapter: an in endpoint, an out endpoint, a flow, a test case and a fixture |

It builds and passes the contract suite as written; it speaks HTTP. The comments marked `HTTP:` in the component are where
your protocol goes; the rest (the body streamed through the blob store, the handler's acknowledgement, the deadline, the
stop) is what every adapter keeps. Then change the settings description to your options, and the package's endpoint
files with it.

A consumer that loses its connection after its start fails `broken()` with the cause; leave the waiting and the next
attempt to the node, which stops the consumer and starts a fresh one of the same endpoint after a delay that grows with
each failure in a row. A consumer that cannot break once started, such as a listener whose bind is its start, as in the
sample, keeps the default.

## Test it

```sh
mvn -f ~/acme-adapter/pom.xml verify                                     # the contract tests
bin/rail8 validate --plugins ~/acme-adapter/target/plugin ~/acme-adapter/package
bin/rail8 test --plugins ~/acme-adapter/target/plugin ~/acme-adapter/package   # the flow tests, the mock in the adapter's place
```

## Run it with the UI

```sh
bin/run-adapter ~/acme-adapter
```

It builds the adapter's jar, starts an empty Postgres of its own in Docker, starts a node with the adapter's
`target/plugin` as its plugins (admin API on `127.0.0.1:18081`), deploys `package/`, starts a partner that answers every
request with the body it was sent on `127.0.0.1:18091`, and runs the UI on **http://localhost:5173**. The ports 18081,
18091 and 5173 must be free; the quickstart's node may keep running, its UI may not.

- **Endpoints** lists the endpoint files of the adapter's folder, which is the run's working folder. An endpoint's form is
  drawn from your settings description. Edit, change a setting, Check, and Save writes the file in your folder.
- **Send a test message** through the adapter. For the template's package, from another terminal:

  ```sh
  curl -X POST --data-binary @$HOME/acme-adapter/package/fixtures/order.xml -H 'Content-Type: application/xml' localhost:18090/orders
  ```

  The flow keys the order by its id and sends it out through the adapter's producer to the partner, whose answer is the
  reply: `<Order><Id>7</Id></Order>`.
- **Messages** finds it: key name `orderId`, value `7`. Its timeline shows the steps `ingress`, `orderId` and `partner`.

Ctrl+C stops everything and removes the Postgres container. The node's log is `target/run-adapter/node.log`. Every run
starts empty: after a change to the adapter, or a Save you want deployed, run `bin/run-adapter` again.

## Install it on a node

Put the jar, or the folder of jars, into the folder that `RAIL8_PLUGINS` names and restart the node. The node logs the
adapter's name, version, api version and the SHA-256 of its jars.
