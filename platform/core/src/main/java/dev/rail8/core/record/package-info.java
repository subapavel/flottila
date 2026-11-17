/**
 * Recording: a {@link dev.rail8.core.record.Snapshot} per step through a
 * {@link dev.rail8.core.record.MessageStore} — in memory here, Postgres in the runtime — under the flow's
 * {@link dev.rail8.core.record.RecordingPolicy}; {@link dev.rail8.core.record.Recorder} is the stage wrapper the flow
 * builder adds, and a run's {@link dev.rail8.core.record.Trail} holds its snapshots until their bodies are durable.
 *
 * @since 0.1
 */
package dev.rail8.core.record;
