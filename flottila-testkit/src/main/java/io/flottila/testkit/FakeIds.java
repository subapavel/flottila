package io.flottila.testkit;

import io.flottila.api.IdGenerator;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic ids: {@code prefix-1}, {@code prefix-2}, ... Every instance counts on its own.
 *
 * @since 0.1
 */
public final class FakeIds implements IdGenerator {

    private final String prefix;
    private final AtomicLong counter = new AtomicLong();

    public FakeIds(String prefix) {
        this.prefix = Objects.requireNonNull(prefix, "prefix");
    }

    @Override public String next() { return prefix + "-" + counter.incrementAndGet(); }

    /** How many ids this generator has handed out. */
    public long issued() { return counter.get(); }
}
