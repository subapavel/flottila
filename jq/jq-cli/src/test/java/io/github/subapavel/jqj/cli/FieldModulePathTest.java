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
 * The first of ADR-0013's shipped module-path cases (#38, spec story 52): {@code -c .a} through the command line, with
 * jq-cli and jq-core as the named modules the launcher puts on its module path. It needs the module-path test
 * execution of this module's build.
 */
class FieldModulePathTest {

  @Test
  void compactFieldRunsOnTheModulePath() {
    Module cli = Main.class.getModule();
    Module core = JqEngine.class.getModule();
    assertThat(cli.getName()).as("jq-cli runs as a named module").isEqualTo("io.github.subapavel.jqj.cli");
    assertThat(core.getName()).as("jq-core runs as a named module").isEqualTo("io.github.subapavel.jqj");
    assertThat(cli.canRead(core)).as("jq-cli reads jq-core through its module descriptor").isTrue();

    // jq-ref 1.8.2, recorded in diff/cli.rec (cli/double-dash-stdin): printf '{"a":1}\n' | jq-ref -c -- .a -> 1, rc 0.
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int rc =
        Main.run(
            new Invocation(
                List.of("-c", "--", ".a"), Map.of(), Path.of("."), new ByteArrayInputStream("{\"a\":1}\n".getBytes(UTF_8)), out, err));

    assertThat(err.toString(UTF_8)).as("stderr").isEmpty();
    assertThat(rc).as("exit code").isZero();
    assertThat(out.toString(UTF_8)).as("stdout").isEqualTo("1\n");
  }
}
