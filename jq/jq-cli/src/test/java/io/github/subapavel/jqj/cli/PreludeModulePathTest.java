package io.github.subapavel.jqj.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqEngine;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ADR-0013's shipped module path with the prelude (#71, ADR-0042): {@code -c map(.a)} through the command line, with
 * jq-cli and jq-core as named modules, so builtin.jq is read from inside jq-core's module, where its package is not
 * exported. It needs the module-path test execution of this module's build.
 */
class PreludeModulePathTest {

  @Test
  void mapRunsOnTheModulePath() {
    Module cli = Main.class.getModule();
    Module core = JqEngine.class.getModule();
    assertThat(cli.getName()).as("jq-cli runs as a named module").isEqualTo("io.github.subapavel.jqj.cli");
    assertThat(core.getName()).as("jq-core runs as a named module").isEqualTo("io.github.subapavel.jqj");

    // jq-ref 1.8.2, recorded in diff/prelude.rec (prelude/map): printf '[{"a":1},{"a":2}]\n' | jq-ref -c 'map(.a)'
    // -> [1,2], rc 0.
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int rc =
        Main.run(
            new Invocation(
                List.of("-c", "map(.a)"),
                Map.of(),
                Path.of("."),
                new ByteArrayInputStream("[{\"a\":1},{\"a\":2}]\n".getBytes(UTF_8)),
                out,
                err));

    assertThat(err.toString(UTF_8)).as("stderr").isEmpty();
    assertThat(rc).as("exit code").isZero();
    assertThat(out.toString(UTF_8)).as("stdout").isEqualTo("[1,2]\n");
  }
}
