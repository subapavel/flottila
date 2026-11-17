package dev.rail8.cli;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates the test JVM's temporary directory ({@code java.io.tmpdir}) when the test run opens, before any test makes a
 * temporary file. The build gives each test JVM a directory of its own that does not exist yet; without it a test that
 * lists the temporary directory would also see the files of the test JVMs running beside it.
 */
public final class ForkTempDir implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        try {
            Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
