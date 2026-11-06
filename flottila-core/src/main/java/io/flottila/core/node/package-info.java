/**
 * The node lease and its self-fence (D-101 (1), (2); ARCHITECTURE §4, §7; F-28): the membership store's interface and the
 * in-memory one, the lease a node renews, whose fence its at-least-once sends and settles check, and the failure of a fenced
 * send.
 *
 * @since 0.1
 */
package io.flottila.core.node;
