/**
 * The {@code http} component (ARCHITECTURE §4, §7): {@link io.flottila.components.http.HttpComponent} — the Vert.x
 * server as the consumer (sync reply, 202, 413, 503 + Retry-After) and the client as the producer (body streamed and
 * re-opened per send, the response as the reply). Depends on flottila-api only.
 *
 * @since 0.1
 */
package io.flottila.components.http;
