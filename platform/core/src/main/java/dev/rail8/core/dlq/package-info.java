/**
 * The dead letter queue (ARCHITECTURE §4, §6): {@link dev.rail8.core.dlq.Parked} rows by reference through a
 * {@link dev.rail8.core.dlq.Dlq} — in memory here, Postgres in the runtime —, parked by the
 * {@link dev.rail8.core.dlq.Parking} wrapper the flow builder adds to at-least-once flows. A row refers to a body that is
 * durable: a park persists the parked body in its own store before it writes the row, and writes none when that fails (D-062).
 *
 * @since 0.1
 */
package dev.rail8.core.dlq;
