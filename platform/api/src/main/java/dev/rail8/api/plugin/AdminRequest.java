package dev.rail8.api.plugin;

import java.time.Clock;
import java.util.Objects;

/**
 * One call of an {@link AdminService}.
 *
 * @param json  the body of a POST as the client sent it, decoded from UTF-8 and not parsed; {@code ""} for a GET
 * @param clock the node's clock
 * @since 0.3
 */
public record AdminRequest(String json, Clock clock) {
    public AdminRequest {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(clock, "clock");
    }
}
