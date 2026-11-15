package io.flottila.mapping.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The corpus fixtures of the service tests and their oracles. Every expected value here comes from
 * a file of the corpus, from {@code EngineFacadeTest} or from {@code sha256sum}, never from the
 * service.
 */
final class Fixtures {

  private Fixtures() {}

  static final String NS = "urn:flottila:corpus:skeleton";

  /** The root element of {@code order.xsd}, as a root selector names it. */
  static final String ORDER = "Q{" + NS + "}Order";

  /**
   * The fingerprint of {@code order.xsd} under {@link #ORDER}: the corpus mapping's, and {@code
   * EngineFacadeTest.FINGERPRINT}.
   */
  static final String FINGERPRINT =
      "sha256:df1714b8b2e4b09e326626a2ea72780bf6298a0ca733c69f5586ea657e4bd90d";

  /**
   * The ETag of the corpus mapping: {@code sha256sum corpus/mappings/order-id-copy.mapping.json}, a
   * file already in canonical form.
   */
  static final String CORPUS_MAPPING_ETAG =
      "\"sha256:57e4430fd26dcadc7d40f72fd4b48bb6cd48d97cf86293e77946e71e6fe8578e\"";

  /** The ETag of {@link #unwired()}: {@code sha256sum} of those bytes. */
  static final String UNWIRED_ETAG =
      "\"sha256:1d39875e6253f99d3d01f14798a2113b4543ea72df1d1545a6257b81f7893001\"";

  /**
   * A new mapping from {@code order.xsd} to itself, created from the directory next to {@code
   * structures/}, in canonical form: the engine's canonical writer, whose tests own the format
   * (ADR-0010). An empty {@code targetFieldMappings} is not written.
   */
  static final String NEW_MAPPING =
      """
      {
        "formatVersion": 1,
        "source": {
          "bundle": [
            "../structures/skeleton/order.xsd"
          ],
          "fingerprint": "sha256:df1714b8b2e4b09e326626a2ea72780bf6298a0ca733c69f5586ea657e4bd90d",
          "root": {
            "element": "Q{urn:flottila:corpus:skeleton}Order"
          }
        },
        "target": {
          "bundle": [
            "../structures/skeleton/order.xsd"
          ],
          "fingerprint": "sha256:df1714b8b2e4b09e326626a2ea72780bf6298a0ca733c69f5586ea657e4bd90d",
          "root": {
            "element": "Q{urn:flottila:corpus:skeleton}Order"
          }
        }
      }
      """;

  /** The ETag of {@link #NEW_MAPPING}: {@code sha256sum} of its bytes. */
  static final String NEW_MAPPING_ETAG =
      "\"sha256:51535d0aefaec12efa302bfb41edc0c8b157e2a40d86d35d2b206cab21832231\"";

  /**
   * A mapping created with only its id (ADR-0029, #153): no structure until the Signature tab
   * chooses them, in canonical form, checked against {@code JSON.stringify}.
   */
  static final String BARE_MAPPING = "{\n  \"formatVersion\": 1\n}\n";

  /** The ETag of {@link #BARE_MAPPING}: {@code sha256sum} of its bytes. */
  static final String BARE_MAPPING_ETAG =
      "\"sha256:1fe9d6e3dbb25864055716be7631a1aff574aeae152ff231921d4048d7c57746\"";

  /** A mapping description (ADR-0029) with a character outside ASCII. */
  static final String DESCRIPTION = "Bestellungen zu Rechnungen – Entwurf";

  /** A mapping created with its id and {@link #DESCRIPTION}, in canonical form. */
  static final String DESCRIBED_MAPPING =
      """
      {
        "description": "Bestellungen zu Rechnungen – Entwurf",
        "formatVersion": 1
      }
      """;

  /** The ETag of {@link #DESCRIBED_MAPPING}: {@code sha256sum} of its bytes. */
  static final String DESCRIBED_MAPPING_ETAG =
      "\"sha256:92a9034b851e89532b801986f75e3462af89634bcd1179d4effca7b1859c94e5\"";

  /** {@link #NEW_MAPPING} created with {@link #DESCRIPTION}, in canonical form. */
  static final String DESCRIBED_NEW_MAPPING =
      """
      {
        "description": "Bestellungen zu Rechnungen – Entwurf",
        "formatVersion": 1,
        "source": {
          "bundle": [
            "../structures/skeleton/order.xsd"
          ],
          "fingerprint": "sha256:df1714b8b2e4b09e326626a2ea72780bf6298a0ca733c69f5586ea657e4bd90d",
          "root": {
            "element": "Q{urn:flottila:corpus:skeleton}Order"
          }
        },
        "target": {
          "bundle": [
            "../structures/skeleton/order.xsd"
          ],
          "fingerprint": "sha256:df1714b8b2e4b09e326626a2ea72780bf6298a0ca733c69f5586ea657e4bd90d",
          "root": {
            "element": "Q{urn:flottila:corpus:skeleton}Order"
          }
        }
      }
      """;

  /** The ETag of {@link #DESCRIBED_NEW_MAPPING}: {@code sha256sum} of its bytes. */
  static final String DESCRIBED_NEW_MAPPING_ETAG =
      "\"sha256:0e0870685a84d848db0743544144c1b45bd57ca02fe77f2d636a740ce78b1be1\"";

  /**
   * The corpus mapping with a description and a layout of its one target field mapping (ADR-0029),
   * in canonical form, checked against {@code JSON.stringify}.
   */
  static final String STUDIO_MAPPING =
      """
      {
        "description": "Copies the order id",
        "formatVersion": 1,
        "source": {
          "bundle": [
            "../structures/skeleton/order.xsd"
          ],
          "fingerprint": "sha256:df1714b8b2e4b09e326626a2ea72780bf6298a0ca733c69f5586ea657e4bd90d",
          "root": {
            "element": "Q{urn:flottila:corpus:skeleton}Order"
          }
        },
        "target": {
          "bundle": [
            "../structures/skeleton/order.xsd"
          ],
          "fingerprint": "sha256:df1714b8b2e4b09e326626a2ea72780bf6298a0ca733c69f5586ea657e4bd90d",
          "root": {
            "element": "Q{urn:flottila:corpus:skeleton}Order"
          }
        },
        "targetFieldMappings": {
          "/Q{urn:flottila:corpus:skeleton}Order/OrderId": {
            "boxes": {
              "a1": {
                "field": "/Q{urn:flottila:corpus:skeleton}Order/OrderId",
                "kind": "source"
              }
            },
            "id": "t1",
            "layout": {
              "boxes": {
                "a1": {
                  "x": -120,
                  "y": 48
                }
              },
              "target": {
                "x": 360,
                "y": 48
              }
            },
            "result": "a1"
          }
        }
      }
      """;

  /** The ETag of {@link #STUDIO_MAPPING}: {@code sha256sum} of its bytes. */
  static final String STUDIO_MAPPING_ETAG =
      "\"sha256:c314903c366e468a4482816bde25983e7a9883734f78212e399d87a61d4e46a0\"";

  /** The input of {@code EngineFacadeTest.compileAndRunCopyTheFieldThroughTheChain}. */
  static final String INPUT =
      "<src:Order xmlns:src=\""
          + NS
          + "\" channel=\"web\"><OrderId>4711</OrderId><Quantity>3</Quantity></src:Order>";

  /** The output of {@code EngineFacadeTest.compileAndRunCopyTheFieldThroughTheChain}. */
  static final String OUTPUT =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ns0:Order xmlns:ns0=\""
          + NS
          + "\"><OrderId>4711</OrderId></ns0:Order>";

  /** The repository root: the nearest ancestor of the module directory with mvnw and corpus/. */
  static Path repositoryRoot() {
    for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
      if (Files.isRegularFile(p.resolve("mvnw")) && Files.isDirectory(p.resolve("corpus"))) {
        return p;
      }
    }
    throw new IllegalStateException("no repository root above " + Path.of("").toAbsolutePath());
  }

  /** {@code corpus/mappings/order-id-copy.mapping.json}: the wired field link, canonical. */
  static byte[] corpusMapping() {
    try {
      return Files.readAllBytes(
          repositoryRoot().resolve("corpus/mappings/order-id-copy.mapping.json"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The corpus mapping with its target field mapping unconnected ({@code result: null}). */
  static byte[] unwired() {
    String wired = new String(corpusMapping(), UTF_8);
    String unwired = wired.replace("\"result\": \"a1\"", "\"result\": null");
    assertThat(unwired).as("the corpus mapping has one result to unwire").isNotEqualTo(wired);
    return unwired.getBytes(UTF_8);
  }

  /**
   * The corpus mapping as an editor's working copy: the same model with {@code result} (a JSON
   * value), its keys out of order and on one line, so it is never in canonical form.
   */
  static String workingCopy(String result) {
    String side =
        """
        {"root": {"element": "%s"}, "fingerprint": "%s", "bundle": ["../structures/skeleton/order.xsd"]}"""
            .formatted(ORDER, FINGERPRINT);
    return """
        {"targetFieldMappings": {"/%1$s/OrderId": {"result": %2$s, "id": "t1", \
        "boxes": {"a1": {"kind": "source", "field": "/%1$s/OrderId"}}}}, \
        "target": %3$s, "source": %3$s, "formatVersion": 1}
        """
        .formatted(ORDER, result, side);
  }

  /**
   * A working copy over {@code order.xsd} with {@code OrderId <- a1} at t1 (1..1) and {@code Note
   * <- result} at t2 (0..unbounded), whose boxes are {@code boxes}; {@code NOTE} in them stands for
   * the source field {@code Note}.
   */
  static String notesThrough(String result, String boxes) {
    String side =
        """
        {"bundle": ["../structures/skeleton/order.xsd"], "fingerprint": "%s", \
        "root": {"element": "%s"}}"""
            .formatted(FINGERPRINT, ORDER);
    return """
        {"formatVersion": 1, "source": %2$s, "target": %2$s, "targetFieldMappings": {
          "/%1$s/OrderId": {"id": "t1", "result": "a1",
            "boxes": {"a1": {"kind": "source", "field": "/%1$s/OrderId"}}},
          "/%1$s/Note": {"id": "t2", "result": "%3$s", "boxes": {%4$s}}}}
        """
        .formatted(ORDER, side, result, boxes.replace("NOTE", "/" + ORDER + "/Note"));
  }

  /**
   * {@code Note <- d1}, d1 dateTrans over the source Note from {@code yyyy-MM-dd HH:mmXXX} to
   * {@code HH:mm XXX}: the Note's time written in the run's time zone (#80).
   */
  static String notesInTheRunsZone() {
    return notesThrough(
        "d1",
        """
        "d1": {"kind": "function", "fn": "dateTrans", "inputs": ["a2"],
               "params": {"sourceFormat": "yyyy-MM-dd HH:mmXXX", "targetFormat": "HH:mm XXX"}},
        "a2": {"kind": "source", "field": "NOTE"}""");
  }

  /** OrderId 1 and the one Note {@code 2026-11-04 10:00Z}, a time in UTC. */
  static final String NOTE_AT_10_UTC =
      "<src:Order xmlns:src=\""
          + NS
          + "\"><OrderId>1</OrderId><Note>2026-11-04 10:00Z</Note></src:Order>";

  /** Compares as text first, so a failure shows the difference, then byte for byte. */
  static void assertBytes(byte[] actual, byte[] expected) {
    assertThat(new String(actual, UTF_8)).isEqualTo(new String(expected, UTF_8));
    assertThat(actual).isEqualTo(expected);
  }

  static byte[] bytes(String text) {
    return text.getBytes(UTF_8);
  }
}
