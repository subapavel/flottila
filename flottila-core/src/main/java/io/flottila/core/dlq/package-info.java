/**
 * The dead letter queue (ARCHITECTURE §4, §6): {@link io.flottila.core.dlq.Parked} rows by reference through a
 * {@link io.flottila.core.dlq.Dlq} — in memory here, Postgres in the runtime —, parked by the
 * {@link io.flottila.core.dlq.Parking} wrapper the flow builder adds to at-least-once flows.
 *
 * @since 0.1
 */
package io.flottila.core.dlq;
