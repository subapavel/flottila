package io.flottila.runtime.deploy;

import io.flottila.runtime.node.OnVirtualThread;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * An {@link ArchiveStore} at the SPI boundary of {@code Deployments} (P1-09c3a test fixture): a {@link DirArchiveStore} over
 * {@code dir} — the dev default {@code <dataDir>/archives}, so a deploy that deleted an object there would show — that records every
 * put, traces it ({@code "put <sha256>"} at the call and {@code "stored <sha256>"} once it returned, on the calling thread), notes
 * whether the calling thread was virtual, and can be told to fail the next put with an {@link IOException}, storing nothing.
 */
final class Shelf implements ArchiveStore {

    private final DirArchiveStore objects;
    private final Path reads;
    private final Consumer<String> trace;
    private final List<String> puts = new CopyOnWriteArrayList<>();
    private final List<String> platformPuts = new CopyOnWriteArrayList<>();
    private final AtomicInteger read = new AtomicInteger();
    private volatile IOException failNext;

    /** Objects under {@code dir}; {@link #bytes} fetches into {@code reads}. */
    Shelf(Path dir, Path reads, Consumer<String> trace) {
        this.objects = new DirArchiveStore(dir);
        this.reads = Objects.requireNonNull(reads, "reads");
        this.trace = Objects.requireNonNull(trace, "trace");
    }

    /** The next put throws {@code failure} and stores nothing. */
    void failNextPut(IOException failure) {
        failNext = Objects.requireNonNull(failure, "failure");
    }

    /** The SHA-256 of every put call, in order, the failed ones included. */
    List<String> puts() {
        return List.copyOf(puts);
    }

    /** The puts called on a platform thread (each by its key): a blocking put belongs on the virtual thread. */
    List<String> platformPuts() {
        return List.copyOf(platformPuts);
    }

    /** The object stored under {@code sha256}, fetched on a virtual thread; fails the case when there is none. */
    byte[] bytes(String sha256) throws Exception {
        Path target = Files.createDirectories(reads).resolve("read-" + read.incrementAndGet() + ".zip");
        OnVirtualThread.call(() -> {
            objects.fetch(sha256, target);
            return null;
        });
        return Files.readAllBytes(target);
    }

    /** Whether an object is stored under {@code sha256}. */
    boolean holds(String sha256) throws Exception {
        try {
            bytes(sha256);
            return true;
        } catch (NoSuchFileException absent) {
            return false;
        }
    }

    @Override
    public void put(String sha256, Path archive) throws IOException {
        puts.add(sha256);
        if (!Thread.currentThread().isVirtual()) platformPuts.add(sha256);
        trace.accept("put " + sha256);
        IOException failure = failNext;
        failNext = null;
        if (failure != null) throw failure;
        objects.put(sha256, archive);
        trace.accept("stored " + sha256);
    }

    @Override
    public void fetch(String sha256, Path target) throws IOException {
        trace.accept("fetch " + sha256);
        objects.fetch(sha256, target);
    }
}
