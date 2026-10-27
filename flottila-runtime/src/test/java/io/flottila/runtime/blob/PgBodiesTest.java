package io.flottila.runtime.blob;

import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The durable tier's bound (D-058, D-061): finite, at least one byte, at most 64 MiB — never unlimited (CLAUDE.md). */
class PgBodiesTest {

    static final long MIB = 1L << 20;

    @Test
    void theBoundIsAtLeastOneByteAndAtMost64MiB() {
        Vertx vertx = Vertx.vertx();
        try {
            // nothing listens on port 1: building a pool connects nowhere, and nothing here queries it
            Pool pool = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost("127.0.0.1").setPort(1).setDatabase("none").setUser("none"))
                    .using(vertx).build();
            try {
                for (long refused : new long[] {Long.MIN_VALUE, -1, 0, 64 * MIB + 1, Long.MAX_VALUE}) {
                    assertThrows(IllegalArgumentException.class, () -> new PgBodies(pool, refused), "a bound of " + refused + " bytes");
                }
                assertEquals(1, new PgBodies(pool, 1).maxBytes(), "one byte is a bound");
                assertEquals(MIB, new PgBodies(pool, MIB).maxBytes(), "the default of FLOTTILA_BLOB_DURABLE_MAX");
                assertEquals(64 * MIB, new PgBodies(pool, 64 * MIB).maxBytes(), "64 MiB is the largest");
            } finally {
                pool.close().await();
            }
        } finally {
            vertx.close().await();
        }
    }
}
