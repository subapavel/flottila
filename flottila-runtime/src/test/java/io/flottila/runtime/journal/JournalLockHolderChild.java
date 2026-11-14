package io.flottila.runtime.journal;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The child JVM of {@code NodeJournalIT.aSecondProcessIsRefused}: takes the lock of {@code <args[0]>/LOCK} as another
 * process would (a whole-file lock on the file the journal locks), prints {@code locked}, and holds it until its standard
 * input ends; prints {@code busy} and exits 3 when the lock is held already.
 */
public final class JournalLockHolderChild {

    private JournalLockHolderChild() { }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createDirectories(Path.of(args[0]));
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        try (FileChannel ch = FileChannel.open(dir.resolve("LOCK"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock = ch.tryLock();
            if (lock == null) {
                out.println("busy");
                System.exit(3);
            }
            out.println("locked");
            while (System.in.read() >= 0) {
                // holds the lock until the parent closes our standard input
            }
            out.println("released");
        }
    }
}
