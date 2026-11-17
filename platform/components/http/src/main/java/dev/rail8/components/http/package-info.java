/**
 * The {@code http} component: {@link dev.rail8.components.http.HttpComponent} — the Vert.x
 * server as the consumer (sync reply, 202, 413, 503 + Retry-After) and the client as the producer (body streamed and
 * re-opened per send, the response as the reply). Depends on rail8-api only.
 *
 * @since 0.1
 */
package dev.rail8.components.http;
