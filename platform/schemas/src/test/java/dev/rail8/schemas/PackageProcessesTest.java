package dev.rail8.schemas;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static dev.rail8.schemas.PackageTest.IN;
import static dev.rail8.schemas.PackageTest.PACKAGE;
import static dev.rail8.schemas.PackageTest.PARTNER;
import static dev.rail8.schemas.PackageTest.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The steps of a flow's local processes are steps of the package like the flow's own: the endpoints and files they name are
 * checked, and a problem line names the process and the step.
 */
class PackageProcessesTest {

    /** A schema-valid flow {@code orders} from {@code in} that calls process {@code enrich}, whose steps are {@code steps}. */
    static String flowWithProcess(String steps) {
        return """
                schemaVersion: 1
                kind: flow
                id: orders
                version: 1.0.0
                from: { endpoint: in }
                delivery: { mode: sync }
                limits: { maxBodyBytes: 1024 }
                steps: [ { type: process, name: run, process: enrich }, { type: to, name: send, endpoint: partner } ]
                processes:
                  enrich:
                    steps: [ %s ]
                """.formatted(steps);
    }

    static Package load(Path dir, String steps) throws IOException {
        write(dir, "package.yaml", PACKAGE);
        write(dir, "endpoints/in.yaml", IN);
        write(dir, "endpoints/partner.yaml", PARTNER);
        String flow = flowWithProcess(steps);
        assertTrue(Schemas.FLOW.validate(Documents.yaml(flow)).valid(), "fixture: the schema accepts the flow: " + Schemas.FLOW.validate(Documents.yaml(flow)));
        write(dir, "flows/orders.yaml", flow);
        return Package.load(dir);
    }

    @Test
    void anEndpointAStepOfAProcessNamesMustBeInThePackage(@TempDir Path dir) throws IOException {
        List<String> problems = load(dir, "{ type: to, name: copy, endpoint: nowhere }").problems();

        assertTrue(problems.contains("flow 'orders' process 'enrich' step 'copy': endpoint 'nowhere' is not in this package"), "the line: " + problems);
    }

    @Test
    void aFileAStepOfAProcessNamesMustExist(@TempDir Path dir) throws IOException {
        List<String> problems = load(dir, "{ type: xslt, name: map, stylesheet: flows/missing.xsl }").problems();

        assertTrue(problems.contains("flows/orders.yaml: flow 'orders' process 'enrich' step 'map' stylesheet 'flows/missing.xsl' does not exist"),
                "the line: " + problems);
    }

    @Test
    void aYamlFileAStepOfAProcessNamesIsNoMisplacedDocument(@TempDir Path dir) throws IOException {
        write(dir, "flows/shape.yaml", "anything: without a kind\n");

        Package pkg = load(dir, "{ type: xsd, name: check, schema: flows/shape.yaml }");

        assertEquals(List.of(), pkg.problems(), "a file a process's step names is that step's file, not a document of no kind");
    }

    @Test
    void writtenStepsListsTheFlowsStepsThenEachProcesssStepsLabelledByTheirProcess() {
        JsonObject flow = Documents.yaml(flowWithProcess("{ type: to, name: copy, endpoint: partner }")
                + "  spare:\n    steps: [ { type: filter, name: keep, when: /a } ]\n");

        List<String> labels = Package.writtenSteps(flow).stream().map(Package.WrittenStep::label).toList();

        assertEquals(List.of("step 'run'", "step 'send'", "process 'enrich' step 'copy'", "process 'spare' step 'keep'"), labels);
        assertEquals(Optional.of("enrich"), Package.writtenSteps(flow).get(2).process());
        assertEquals("partner", Package.writtenSteps(flow).get(2).entry().getString("endpoint"));
    }
}
