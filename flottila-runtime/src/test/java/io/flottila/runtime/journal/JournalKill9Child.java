package io.flottila.runtime.journal;

import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The child JVM of {@link JournalKill9IT}: opens a journal over the real {@link JournalFiles} in {@code args[0]} and lets
 * {@value #APPENDERS} virtual threads append without pause; each record is printed as
 * {@code ack <message id> <segment> <position> <length> end} only after its {@code forced} future succeeded, so every
 * complete line names an acknowledged record. The parent kills it with -9 while it appends.
 *
 * <p>Small segments make the run roll and seal while it is killed; the fake clock never moves (no age roll), so what
 * rolls is decided by records and bytes alone.
 */
public final class JournalKill9Child {

    static final int APPENDERS = 8;
    static final int PER_APPENDER = 1_000_000;

    private JournalKill9Child() { }

    /** The body size of a message id: the parent recomputes it to compare byte for byte. */
    static int size(String id) {
        return (int) Math.floorMod(id.hashCode() * 2_654_435_761L, JournalKill9IT.RECORD_MAX + 1L);
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        FakeClock clock = FakeClock.at(JournalFixture.START);
        Journal journal = Journal.open(JournalKill9IT.config(dir), clock, new FakeTimer(clock), JournalFixture.keys(JournalFixture.KEY),
                JournalFiles.real());
        out.println("open");
        List<Thread> appenders = new ArrayList<>();
        for (int a = 0; a < APPENDERS; a++) {
            int appender = a;
            appenders.add(Thread.ofVirtual().name("appender-" + a).start(() -> {
                for (int i = 0; i < PER_APPENDER; i++) {
                    String id = "k9-" + appender + "-" + i;
                    try {
                        Journal.Appended appended = journal.append(JournalFixture.record(id, size(id)), JournalFixture.live());
                        appended.forced().await();
                        JournalRef r = appended.ref();
                        synchronized (out) {
                            out.println("ack " + id + " " + r.segment() + " " + r.position() + " " + r.length() + " end");
                        }
                    } catch (Throwable t) {
                        synchronized (out) {
                            out.println("error " + id + " " + t);
                        }
                        return;
                    }
                }
            }));
        }
        for (Thread t : appenders) t.join();
        out.println("done");
    }
}
