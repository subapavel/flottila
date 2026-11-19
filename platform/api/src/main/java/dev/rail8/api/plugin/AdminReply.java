package dev.rail8.api.plugin;

import java.util.Objects;

/**
 * The answer to one call of an {@link AdminService}. A valid reply is at most {@link AdminService#MAX_REPLY_BYTES} of JSON in
 * UTF-8 and is one of:
 * <ul>
 * <li>200 or 201 with any JSON value, sent as {@code application/json};</li>
 * <li>400 to 599 with a problem (RFC 9457), a JSON object whose {@code status} is the reply's, sent as
 *     {@code application/problem+json}.</li>
 * </ul>
 * The node answers any other reply with a 500 and logs it.
 *
 * @param status the HTTP status
 * @param json   the body
 * @since 0.3
 */
public record AdminReply(int status, String json) {
    public AdminReply {
        Objects.requireNonNull(json, "json");
    }
}
