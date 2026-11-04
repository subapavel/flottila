package io.flottila.api;

import java.util.Objects;

/**
 * A component's health as reported to the admin API.
 *
 * @since 0.1
 */
public record Health(Status status, String detail) {

    /**
     * {@code DEGRADED}: working, part of it held back until a dependency answers again (the admin's overall status stays up).
     *
     * @since 0.1
     */
    public enum Status { UP, DEGRADED, DOWN }

    public Health { Objects.requireNonNull(status, "status"); }

    public static Health up() { return new Health(Status.UP, ""); }

    public static Health down(String detail) { return new Health(Status.DOWN, detail); }

    public static Health degraded(String detail) { return new Health(Status.DEGRADED, detail); }
}
