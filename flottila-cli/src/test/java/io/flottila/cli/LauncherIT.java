package io.flottila.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.flottila.cli.CliRuns.REPO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-03a (D-079): the repository's {@code bin/flottila} — a POSIX sh script, LF line endings, executable — runs the packaged cli
 * jar ({@code flottila-cli/target/flottila-cli-*.jar}, manifest main class {@code io.flottila.cli.Cli}, its class path in
 * {@code target/lib}) with its arguments, and the process exits with the command's code. Runs after {@code package} (failsafe),
 * from the repository root as CI does ({@code bin/flottila validate packages/demo}), in a cleared environment: PATH with this JVM's
 * {@code java} first, JAVA_HOME, HOME (an inherited JAVA_TOOL_OPTIONS would add a stderr line).
 */
class LauncherIT {

    static final Path LAUNCHER = REPO.resolve("bin/flottila");
    static final long BOUND_SECONDS = 120;

    @TempDir Path tmp;

    record Exited(int code, String out, String err) {
        @Override public String toString() { return "exit " + code + "\n--- out\n" + out + "--- err\n" + err; }
    }

    Exited launch(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(LAUNCHER.toString());
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command).directory(REPO.toFile());
        Map<String, String> env = pb.environment();
        String path = System.getenv("PATH");
        env.clear();
        Path javaHome = Path.of(System.getProperty("java.home"));
        env.put("PATH", javaHome.resolve("bin") + (path == null ? "" : ":" + path));
        env.put("JAVA_HOME", javaHome.toString());
        env.put("HOME", System.getProperty("user.home"));
        Path out = tmp.resolve("out-" + System.nanoTime() + ".txt");
        Path err = tmp.resolve("err-" + System.nanoTime() + ".txt");
        Process p = pb.redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        if (!p.waitFor(BOUND_SECONDS, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            p.waitFor(BOUND_SECONDS, TimeUnit.SECONDS);
            throw new AssertionError("bin/flottila " + String.join(" ", args) + " did not exit within " + BOUND_SECONDS + " s: "
                    + Files.readString(out) + Files.readString(err));
        }
        return new Exited(p.exitValue(), Files.readString(out, StandardCharsets.UTF_8), Files.readString(err, StandardCharsets.UTF_8));
    }

    @Test
    void theLauncherIsAnExecutableShScriptWithLfEndings() throws IOException {
        assertTrue(Files.isRegularFile(LAUNCHER), "bin/flottila exists");
        assertTrue(Files.isExecutable(LAUNCHER), "bin/flottila is executable (mode 755)");
        String text = Files.readString(LAUNCHER);
        assertTrue(text.startsWith("#!/bin/sh\n") || text.startsWith("#!/usr/bin/env sh\n"), "a sh script: " + text.lines().findFirst().orElse(""));
        assertFalse(text.contains("\r"), "LF line endings only");
    }

    /** CI's step: the demo validates through the launcher, the tree on stdout, exit 0. */
    @Test
    void binFlottilaValidatesTheDemo() throws Exception {
        Exited e = launch("validate", "packages/demo");

        assertEquals(0, e.code(), e.toString());
        assertEquals(ValidateCommandTest.DEMO_PACKAGE_TREE, e.out().lines().toList(), e.toString());
    }

    /** CI's step after validate (P2-03b1, D-080): the demo's test file passes through the launcher, the report on stdout, exit 0. */
    @Test
    void binFlottilaTestDemoExit0() throws Exception {
        Exited e = launch("test", "packages/demo");

        assertEquals(0, e.code(), e.toString());
        assertEquals(TestCommandTest.DEMO_REPORT, e.out().lines().toList(), e.toString());
    }

    /** The process exits with the command's code: 1 for a package with a problem, 2 for an unknown command. */
    @Test
    void theProcessExitsWithTheCommandsCode() throws Exception {
        Exited broken = launch("validate", "flottila-cli/src/test/resources/packages/broken-xslt");
        Exited unknown = launch("frobnicate");

        assertEquals(1, broken.code(), broken.toString());
        assertTrue(broken.out().contains("problem: flow 'invoice-sync' does not build: "), broken.toString());
        assertEquals(2, unknown.code(), unknown.toString());
        assertFalse(unknown.err().isBlank(), "usage on stderr: " + unknown);
    }
}
