/**
 * The body journal (CORE-3, D-105): per-node append-only segment files of encrypted records, one long-lived writer
 * virtual thread that shares one force per channel among every append of a round, sealed segments with a footer index,
 * large bodies spilled as segments of their own and moved in at persist, and a start that recovers by CRC32C alone
 * ({@link io.flottila.runtime.journal.Journal}).
 *
 * @since 0.1
 */
package io.flottila.runtime.journal;
