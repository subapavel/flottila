/**
 * Recording (ARCHITECTURE §6, §8): a {@link io.flottila.core.record.Snapshot} per step through a
 * {@link io.flottila.core.record.MessageStore} — in memory here, Postgres in the runtime — under the flow's
 * {@link io.flottila.core.record.RecordingPolicy}; {@link io.flottila.core.record.Recorder} is the stage wrapper the flow
 * builder adds, and a run's {@link io.flottila.core.record.Trail} holds its snapshots until their bodies are durable (D-064).
 *
 * @since 0.1
 */
package io.flottila.core.record;
