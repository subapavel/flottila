package dev.rail8.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LongPaths#exactlyPathMax} makes the leaf's absolute path exactly {@link LongPaths#PATH_MAX} long whatever the length of
 * the folder above it: a test names that folder after the clock, so its length changes with the machine's uptime.
 */
class LongPathsTest {

    @Test
    void theLeafsAbsolutePathIsExactlyPathMaxForEveryLengthOfTheFolderAboveIt() {
        int cwd = Path.of("").toAbsolutePath().toString().length();
        for (int extra = 0; extra <= LongPaths.LINK.length() + 2; extra++) {
            Path top = Path.of("target", "t" + "x".repeat(extra));
            List<String> below = LongPaths.exactlyPathMax(top, "vendor-deps");
            int length = cwd + 1 + top.toString().length() + below.stream().mapToInt(name -> 1 + name.length()).sum();
            assertEquals(LongPaths.PATH_MAX, length, "with a folder of " + top.toString().length() + " chars above");
            assertTrue(below.stream().allMatch(name -> !name.isEmpty() && name.length() <= 255), "each name 1 to 255 chars: " + top);
        }
    }
}
