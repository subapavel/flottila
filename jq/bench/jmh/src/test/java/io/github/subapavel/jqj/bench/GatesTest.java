package io.github.subapavel.jqj.bench;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The G2, G3 and G4 scenarios' states run one operation within {@code Limits.embedded()} and write what jq writes; and
 * {@link Gates#G7_process_1k}'s exit-code check, against stand-in launchers named by {@code jqj.launcher}: a shot whose
 * process fails must not be timed as a result. The real launcher runs in the spec gate's JMH step.
 */
class GatesTest {
  @TempDir Path dir;

  /** A shell script that exits 0 when it gets {@code -c .a <existing file>}, and 4 otherwise. */
  private static final String CHECKS_ARGS =
      "#!/bin/sh\n[ $# -eq 3 ] && [ \"$1\" = -c ] && [ \"$2\" = .a ] && [ -f \"$3\" ] && exit 0\nexit 4\n";

  private Path launcher(String name, String script) throws IOException {
    Path p = dir.resolve(name);
    Files.writeString(p, script, UTF_8);
    Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwx------"));
    return p;
  }

  /** Runs one G7.process shot with {@code jqj.launcher} set to {@code launcher}, restoring the property after. */
  private static int shot(Path launcher) throws IOException, InterruptedException {
    String before = System.getProperty("jqj.launcher");
    System.setProperty("jqj.launcher", launcher.toString());
    Gates.ColdFile state;
    try {
      state = new Gates.ColdFile();
    } finally {
      if (before == null) {
        System.clearProperty("jqj.launcher");
      } else {
        System.setProperty("jqj.launcher", before);
      }
    }
    state.setUp();
    try {
      return new Gates().G7_process_1k(state);
    } finally {
      state.tearDown();
    }
  }

  /** G2 on orders 1k writes the nine orders over 100 that jq-ref writes for bench/G2.1k. */
  @Test
  void g2At1kWritesTheOrdersOver100() {
    String expected =
        "{\"id\":2,\"total\":158.51}\n{\"id\":4,\"total\":116.89}\n{\"id\":5,\"total\":196.08}\n"
            + "{\"id\":7,\"total\":154.46}\n{\"id\":9,\"total\":112.84}\n{\"id\":10,\"total\":192.03}\n"
            + "{\"id\":12,\"total\":150.41}\n{\"id\":14,\"total\":108.79}\n{\"id\":15,\"total\":187.98}\n";
    Gates.G2At1k s = new Gates.G2At1k();
    s.setUp();
    // setUp ran one operation; the count is cumulative.
    assertThat(s.operation()).isEqualTo(2L * expected.length());
  }

  /** G4 rebuilds its input: [0..9999] and one LF, as many bytes as the ints document of 10⁴ elements. */
  @Test
  void g4At1e4WritesItsInputBackWithinTheEmbeddedLimits() {
    Gates.G4At1e4 s = new Gates.G4At1e4();
    s.setUp();
    assertThat(s.operation()).isEqualTo(2L * Inputs.ints(Inputs.INTS_1E4).length);
  }

  /** G3 on recs 100k completes and writes as many bytes every operation. */
  @Test
  void g3At100kWritesTheSameGroupsEveryOperation() {
    Gates.G3At100k s = new Gates.G3At100k();
    s.setUp();
    long one = s.operation() / 2;
    assertThat(one).isPositive();
    assertThat(s.operation()).isEqualTo(3 * one);
  }

  @Test
  void aLauncherThatExitsZeroOnTheScenariosCommandLineGivesZero() throws IOException, InterruptedException {
    assertThat(shot(launcher("ok", CHECKS_ARGS))).isZero();
  }

  @Test
  void aLauncherThatExitsNonZeroFailsTheShot() throws IOException {
    Path failing = launcher("fails", "#!/bin/sh\nexit 3\n");
    assertThatThrownBy(() -> shot(failing))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(failing + " exited with 3");
  }
}
