package io.flottila.mapping.service;

import static io.flottila.mapping.service.Fixtures.ORDER;
import static io.flottila.mapping.service.Fixtures.assertBytes;
import static io.flottila.mapping.service.Fixtures.bytes;
import static io.flottila.mapping.service.Fixtures.corpusMapping;
import static io.flottila.mapping.service.Fixtures.unwired;
import static io.flottila.mapping.service.Fixtures.workingCopy;
import static io.flottila.mapping.service.Wire.assertProblem;
import static io.flottila.mapping.service.Wire.createRequest;
import static io.flottila.mapping.service.Wire.described;
import static io.flottila.mapping.service.Wire.structureRequest;
import static io.flottila.mapping.service.Wire.testRequest;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.nio.file.Files;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Workspace escapes sent over HTTP (ADR-0014, "References": a mandatory test; ADR-0024). Each
 * attack never gets 200, never shows the outside schema's {@code SecretField}, and leaves {@code
 * outside/} and the directory beside the workspace unchanged. Where the request reaches a resource,
 * the answer is 400 {@code invalid-workspace-path}.
 *
 * <p>The witnesses: {@link #theInsideCopyOfTheOutsideSchemaLoadsAndRuns} shows that a read of the
 * outside schema would show the marker; the escaping ids name {@code outside/evil.mapping.json},
 * which exists; a write through an escape would change the snapshot.
 */
@QuarkusTest
class WorkspaceEscapeTest {

  private static final String MARKER = "SecretField";
  private static final String EXFIL = "Q{urn:flottila:test:outside}Exfil";
  private static final String EXFIL_FINGERPRINT =
      "sha256:b1e7aba69ac4f5572ccadcb28bc162f0cab8d7be4cca3a487fff96340387ad00";
  private static final String EXFIL_INPUT =
      "<o:Exfil xmlns:o=\"urn:flottila:test:outside\"><SecretField>s3cr3t</SecretField></o:Exfil>";

  /** Replaced by the absolute path of {@code outside/} when the attack is sent. */
  private static final String OUTSIDE = "{outside}";

  @TestHTTPResource URI root;

  private Map<String, String> before;

  /**
   * @param reachesAResource false where the router answers before any resource: Vert.x removes the
   *     dot segment {@code %2e%2e} (observed 2026-11-02, Quarkus 3.40.1.1)
   */
  record Attack(String method, String path, String body, boolean reachesAResource) {}

  @BeforeEach
  void snapshot() {
    before = TempWorkspace.besideTheWorkspace();
  }

  @AfterEach
  void nothingBesideTheWorkspaceChanged() {
    assertThat(TempWorkspace.besideTheWorkspace()).isEqualTo(before);
  }

  static Stream<Named<Attack>> attacks() {
    String validModel = new String(corpusMapping(), java.nio.charset.StandardCharsets.UTF_8);
    return Stream.of(
        attack("GET id ..%2F..%2Fetc%2Fpasswd", "GET", "api/mappings/..%2F..%2Fetc%2Fpasswd", ""),
        attack("GET id ..%2Foutside%2Fevil", "GET", "api/mappings/..%2Foutside%2Fevil", ""),
        attack("GET id %2e%2e%2foutside%2fevil", "GET", "api/mappings/%2e%2e%2foutside%2fevil", ""),
        Named.of("GET id %2e%2e", new Attack("GET", "api/mappings/%2e%2e", "", false)),
        attack("GET id %00", "GET", "api/mappings/%00", ""),
        attack("GET id %5C", "GET", "api/mappings/%5C", ""),
        attack("GET id ..%5Coutside%5Cevil", "GET", "api/mappings/..%5Coutside%5Cevil", ""),
        attack("GET through a symlink pointing out", "GET", "api/mappings/links/out", ""),
        attack(
            "structures: an absolute bundle ref",
            "POST",
            "api/structures",
            structureRequest("", OUTSIDE + "/evil.xsd", EXFIL)),
        attack(
            "structures: ..\\.. bundle ref",
            "POST",
            "api/structures",
            structureRequest("structures/skeleton", "..\\..\\..\\outside\\evil.xsd", EXFIL)),
        attack(
            "structures: a symlinked bundle ref",
            "POST",
            "api/structures",
            structureRequest("", "links/out.xsd", EXFIL)),
        attack(
            "structures: a bundle ref through a symlinked directory",
            "POST",
            "api/structures",
            structureRequest("links", "outdir/evil.xsd", EXFIL)),
        attack(
            "structures: base ..",
            "POST",
            "api/structures",
            structureRequest("..", "outside/evil.xsd", EXFIL)),
        attack(
            "create with id ../x",
            "POST",
            "api/mappings",
            createRequest("../x", "workspace/structures/skeleton/order.xsd", ORDER)),
        attack(
            "create with id ../outside/new",
            "POST",
            "api/mappings",
            createRequest("../outside/new", "evil.xsd", EXFIL)),
        attack("PUT through a symlink pointing out", "PUT", "api/mappings/links/out", validModel),
        attack("PUT id ..%2Foutside%2Fevil", "PUT", "api/mappings/..%2Foutside%2Fevil", validModel),
        // the id is checked before the body is read (#28 rulings)
        attack(
            "PUT id ..%2Foutside%2Fevil, malformed body",
            "PUT", "api/mappings/..%2Foutside%2Fevil", "{"),
        attack(
            "PUT through a symlink pointing out, malformed body",
            "PUT",
            "api/mappings/links/out",
            "{\"formatVersion\": 1,}"),
        attack(
            "PUT id ..%2Foutside%2Fevil, a body stage 1 refuses",
            "PUT", "api/mappings/..%2Foutside%2Fevil", "{\"extra\": 1}"),
        // a dangling link fails closed: its target might appear outside later (#28 rulings)
        attack(
            "structures: a bundle ref to a dangling link",
            "POST",
            "api/structures",
            structureRequest("", "links/ghost.xsd", EXFIL)),
        attack(
            "structures: a bundle ref through a dangling directory link",
            "POST",
            "api/structures",
            structureRequest("links", "ghostdir/evil.xsd", EXFIL)),
        attack(
            "create with a bundle ref to a dangling link",
            "POST",
            "api/mappings",
            createRequest("mappings/dangling", "../links/ghost.xsd", EXFIL)),
        attack(
            "test run with a bundle ref to a dangling link",
            "POST",
            "api/mappings/mappings/order-id-copy/test",
            testRequest(exfilModel("../links/ghost.xsd"), EXFIL_INPUT)),
        // a link loop has no real path either: it fails closed (#28 round 3)
        attack(
            "structures: a bundle ref to a link loop",
            "POST",
            "api/structures",
            structureRequest("", "links/loop.xsd", EXFIL)),
        attack(
            "test run with a bundle ref to a link loop",
            "POST",
            "api/mappings/mappings/order-id-copy/test",
            testRequest(exfilModel("../links/loop.xsd"), EXFIL_INPUT)),
        attack(
            "test run with an escaping bundle",
            "POST",
            "api/mappings/mappings/order-id-copy/test",
            testRequest(exfilModel("../../outside/evil.xsd"), EXFIL_INPUT)),
        attack(
            "test run with a symlinked bundle",
            "POST",
            "api/mappings/mappings/order-id-copy/test",
            testRequest(exfilModel("../links/out.xsd"), EXFIL_INPUT)),
        attack(
            "test run with an absolute bundle",
            "POST",
            "api/mappings/mappings/order-id-copy/test",
            testRequest(exfilModel(OUTSIDE + "/evil.xsd"), EXFIL_INPUT)),
        attack(
            "test run through id ..%2Foutside%2Fevil",
            "POST",
            "api/mappings/..%2Foutside%2Fevil/test",
            testRequest(exfilModel("evil.xsd"), EXFIL_INPUT)),
        // #90: the resources no longer check bundle references; every escape below is refused
        // inside the service's reference resolver, so each kind is sent to each endpoint that
        // loads a structure. A missing file outside is an escape, never a schema-unreadable.
        attack(
            "structures: a ../ bundle ref",
            "POST",
            "api/structures",
            structureRequest("", "../outside/evil.xsd", EXFIL)),
        attack(
            "structures: a bundle ref to a missing file outside",
            "POST",
            "api/structures",
            structureRequest("", "../outside/missing.xsd", EXFIL)),
        attack(
            "create with an escaping bundle",
            "POST",
            "api/mappings",
            createRequest("mappings/escaping", "../../outside/evil.xsd", EXFIL)),
        attack(
            "create with an absolute bundle",
            "POST",
            "api/mappings",
            createRequest("mappings/escaping", OUTSIDE + "/evil.xsd", EXFIL)),
        attack(
            "create with a symlinked bundle",
            "POST",
            "api/mappings",
            createRequest("mappings/escaping", "../links/out.xsd", EXFIL)),
        attack(
            "create with a bundle ref to a link loop",
            "POST",
            "api/mappings",
            createRequest("mappings/escaping", "../links/loop.xsd", EXFIL)),
        attack(
            "create with a bundle ref to a missing file outside",
            "POST",
            "api/mappings",
            createRequest("mappings/escaping", "../../outside/missing.xsd", EXFIL)),
        attack(
            "test run with a bundle ref to a missing file outside",
            "POST",
            "api/mappings/mappings/order-id-copy/test",
            testRequest(exfilModel("../../outside/missing.xsd"), EXFIL_INPUT)),
        // #99: refresh loads both sides' bundles through the same resolver; an escape is a 400,
        // never a load diagnostic of its side in a 200
        attack(
            "refresh with an escaping bundle",
            "POST",
            "api/mappings/mappings/order-id-copy/refresh",
            exfilModel("../../outside/evil.xsd")),
        attack(
            "refresh with a symlinked bundle",
            "POST",
            "api/mappings/mappings/order-id-copy/refresh",
            exfilModel("../links/out.xsd")),
        attack(
            "refresh with a bundle ref to a missing file outside",
            "POST",
            "api/mappings/mappings/order-id-copy/refresh",
            exfilModel("../../outside/missing.xsd")),
        attack(
            "refresh through id ..%2Foutside%2Fevil",
            "POST", "api/mappings/..%2Foutside%2Fevil/refresh", exfilModel("evil.xsd")),
        // #105: compile loads both sides' bundles through the same resolver
        attack(
            "compile with an escaping bundle",
            "POST",
            "api/mappings/mappings/order-id-copy/compile",
            exfilModel("../../outside/evil.xsd")),
        attack(
            "compile with a symlinked bundle",
            "POST",
            "api/mappings/mappings/order-id-copy/compile",
            exfilModel("../links/out.xsd")),
        attack(
            "compile through id ..%2Foutside%2Fevil",
            "POST", "api/mappings/..%2Foutside%2Fevil/compile", exfilModel("evil.xsd")),
        // #93: an escape in a multi-file bundle is refused as in a one-file bundle, after a
        // listed file is read as well as before
        attack(
            "structures: an escape after a listed file",
            "POST",
            "api/structures",
            bundleRequest("structures/skeleton", "order.xsd", "../../../outside/evil.xsd")),
        attack(
            "structures: an escape before a listed file",
            "POST",
            "api/structures",
            bundleRequest("structures/skeleton", "../../../outside/evil.xsd", "order.xsd")),
        attack(
            "create with an escape in a two-file bundle",
            "POST",
            "api/mappings",
            "{\"id\": \"mappings/escaping\", \"source\": %1$s, \"target\": %1$s}"
                .formatted(
                    "{\"bundle\": [\"../structures/skeleton/order.xsd\","
                        + " \"../../outside/evil.xsd\"], \"root\": {\"element\": \""
                        + EXFIL
                        + "\"}}")));
  }

  /** A {@code StructureRequest} for {@link #EXFIL} over a bundle of several files. */
  private static String bundleRequest(String base, String... bundle) {
    return "{\"base\": %s, \"bundle\": [%s], \"root\": {\"element\": %s}}"
        .formatted(
            Wire.quoted(base),
            String.join(", ", Stream.of(bundle).map(Wire::quoted).toList()),
            Wire.quoted(EXFIL));
  }

  /**
   * #93 (ADR-0014 "Reference resolver shape (#90)", ADR-0008): an escape written inside a schema,
   * in an {@code xs:import}'s schemaLocation, is mapped to a bundle file by text before any read.
   * It maps to none, so the answer is a 200 {@code refused} with {@code
   * schema-reference-outside-bundle} at the import, and the outside schema is never read: a read
   * would declare the root {@code Exfil} and show {@code SecretField}. {@code
   * WorkspaceTest.aSchemaLocationThatEscapesIsOutsideTheBundleAndNeverRead} observes the opens.
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource({"../../../outside/evil.xsd, 96", "../../../etc/passwd, 90"})
  void anEscapeInASchemaLocationIsOutsideTheBundleAndNeverRead(String location, int column) {
    TempWorkspace.write(
        "structures/escape-import/doc.xsd",
        bytes(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                       targetNamespace="urn:flottila:test:inside">
              <xs:import namespace="urn:flottila:test:outside" schemaLocation="%s"/>
            </xs:schema>
            """
                .formatted(location)));

    Http.Reply reply =
        new Http(root)
            .post(
                "api/structures",
                "application/json",
                bytes(structureRequest("structures/escape-import", "doc.xsd", EXFIL)));

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    assertThat(reply.json().path("kind").textValue()).isEqualTo("refused");
    assertThat(described(reply.json().path("diagnostics")))
        .containsExactly("error schema-reference-outside-bundle at file doc.xsd 4:" + column);
    assertThat(reply.json().path("diagnostics").path(0).path("message").textValue())
        .contains("\"" + location + "\"");
    assertThat(reply.text()).as("never the outside schema").doesNotContain(MARKER);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("attacks")
  void anEscapeIsRefusedBeforeAnyRead(Attack attack) {
    String body = attack.body().replace(OUTSIDE, TempWorkspace.outside().toString());

    Http.Reply reply =
        new Http(root)
            .send(
                attack.method(),
                attack.path(),
                attack.method().equals("GET") ? null : "application/json",
                bytes(body));

    assertThat(reply.status()).as("never served: %s", reply).isNotIn(200, 201);
    assertThat(reply.text()).as("never the outside schema").doesNotContain(MARKER);
    if (attack.reachesAResource()) {
      assertProblem(reply, 400, "invalid-workspace-path");
    } else {
      assertThat(reply.status()).as("the router's answer: %s", reply).isEqualTo(404);
    }
  }

  /** The detail names the id as the request spelled it, not its mapping model file (#54). */
  @Test
  void aRefusedIdIsNamedAsTheRequestSpelledIt() {
    Http http = new Http(root);

    JsonNode file =
        assertProblem(http.get("api/mappings/links/out"), 400, "invalid-workspace-path");
    JsonNode directory =
        assertProblem(
            http.post(
                "api/mappings/links/outdir/evil/test",
                "application/json",
                bytes(testRequest(exfilModel("evil.xsd"), EXFIL_INPUT))),
            400,
            "invalid-workspace-path");

    assertThat(file.path("detail").textValue()).isEqualTo("not a workspace path: \"links/out\"");
    assertThat(directory.path("detail").textValue())
        .isEqualTo("not a workspace path: \"links/outdir/evil\"");
  }

  @Test
  void theInsideCopyOfTheOutsideSchemaLoadsAndRuns() {
    Http http = new Http(root);

    Http.Reply loaded =
        http.post(
            "api/structures",
            "application/json",
            bytes(structureRequest("structures/outside-copy", "evil.xsd", EXFIL)));
    assertThat(loaded.status()).as("%s", loaded).isEqualTo(200);
    assertThat(loaded.json().path("kind").textValue()).isEqualTo("loaded");
    assertThat(loaded.json().path("fingerprint").textValue()).isEqualTo(EXFIL_FINGERPRINT);
    assertThat(loaded.text()).contains(MARKER);

    Http.Reply run =
        http.post(
            "api/mappings/mappings/order-id-copy/test",
            "application/json",
            bytes(testRequest(exfilModel("../structures/outside-copy/evil.xsd"), EXFIL_INPUT)));
    assertThat(run.status()).as("%s", run).isEqualTo(200);
    assertThat(run.json().path("kind").textValue()).isEqualTo("output");
    assertThat(run.json().path("output").textValue()).contains(MARKER);
  }

  /**
   * A pre-existing {@code .<name>.tmp} that is a hard link to a file outside is never written
   * through, and the save still succeeds (#28 rulings). {@link #nothingBesideTheWorkspaceChanged}
   * compares {@code outside/evil.mapping.json}'s bytes as well.
   */
  @Test
  void aSaveNeverWritesThroughAHardLinkedTemporaryFile() throws Exception {
    byte[] outsideBefore = Files.readAllBytes(TempWorkspace.outside().resolve("evil.mapping.json"));
    TempWorkspace.write("saving-hard-link/order.mapping.json", unwired());
    Files.createLink(
        TempWorkspace.root().resolve("saving-hard-link/.order.mapping.json.tmp"),
        TempWorkspace.outside().resolve("evil.mapping.json"));

    Http.Reply put =
        new Http(root)
            .put(
                "api/mappings/saving-hard-link/order",
                "application/json",
                bytes(workingCopy("\"a1\"")));

    assertThat(put.status()).as("%s", put).isEqualTo(200);
    assertBytes(put.body(), corpusMapping());
    assertBytes(TempWorkspace.read("saving-hard-link/order.mapping.json"), corpusMapping());
    assertBytes(
        Files.readAllBytes(TempWorkspace.outside().resolve("evil.mapping.json")), outsideBefore);
  }

  private static Named<Attack> attack(String name, String method, String path, String body) {
    return Named.of(name, new Attack(method, path, body, true));
  }

  /** A mapping that copies the outside schema's {@code SecretField}, its bundle {@code bundle}. */
  private static String exfilModel(String bundle) {
    String side =
        "{\"bundle\": [%s], \"fingerprint\": \"%s\", \"root\": {\"element\": \"%s\"}}"
            .formatted(Wire.quoted(bundle), EXFIL_FINGERPRINT, EXFIL);
    return """
        {"formatVersion": 1, "source": %1$s, "target": %1$s,
         "targetFieldMappings": {"/%2$s/SecretField": {"id": "t1", "result": "a1",
           "boxes": {"a1": {"kind": "source", "field": "/%2$s/SecretField"}}}}}
        """
        .formatted(side, EXFIL);
  }
}
