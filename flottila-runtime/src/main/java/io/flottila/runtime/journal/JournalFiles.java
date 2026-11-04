package io.flottila.runtime.journal;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.Set;

/**
 * Every file channel the journal opens comes from here: the segments, the identity file, the lock and the directories it
 * forces. The real one opens {@link FileChannel}s; a test double records, holds or fails their writes and forces.
 *
 * @since 0.1
 */
@FunctionalInterface
public interface JournalFiles {

    /** Opens {@code file} as {@link FileChannel#open(Path, Set, java.nio.file.attribute.FileAttribute[])} does. */
    FileChannel open(Path file, Set<? extends OpenOption> options) throws IOException;

    /** The file system itself. */
    static JournalFiles real() {
        return FileChannel::open;
    }
}
