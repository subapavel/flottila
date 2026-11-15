/**
 * Recording (ARCHITECTURE §6, §8): a {@link io.rail8.core.record.Snapshot} per step through a
 * {@link io.rail8.core.record.MessageStore} — in memory here, Postgres in the runtime — under the flow's
 * {@link io.rail8.core.record.RecordingPolicy}; {@link io.rail8.core.record.Recorder} is the stage wrapper the flow
 * builder adds, and a run's {@link io.rail8.core.record.Trail} holds its snapshots until their bodies are durable (D-064).
 *
 * @since 0.1
 */
package io.rail8.core.record;
