/**
 * The Postgres transaction path shared by the stores: a pooled connection taken for a transaction, and a refusal by the pool at
 * that acquire as a type of its own (D-133).
 *
 * @since 0.1
 */
package io.flottila.runtime.pg;
