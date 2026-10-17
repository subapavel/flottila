package io.flottila.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Build-configuration guards for the {@code flottila-api} module (P0-01). They fail on JDK 21,
 * on a Vert.x 4 classpath, or when Mutiny leaks onto the api classpath.
 */
class ApiModuleTest {

    @Test
    void buildRunsOnJdk25() {
        assertTrue(Runtime.version().feature() >= 25,
                "api must build and run on JDK 25, found " + Runtime.version());
    }

    @Test
    void vertxFutureIsVersion5WithAwait() throws Exception {
        Class<?> future = Class.forName("io.vertx.core.Future");
        Method await = future.getMethod("await");
        assertEquals(Object.class, await.getReturnType(),
                "Future<T>.await() returns T (erased to Object); it exists only from Vert.x 5 on");
        assertTrue(future.isInterface(), "io.vertx.core.Future is an interface");
    }

    @Test
    void mutinyIsAbsentFromApiClasspath() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("io.smallrye.mutiny.Uni"),
                "api uses the JDK plus Vert.x Future/Buffer/streams and nothing else (CLAUDE.md)");
    }
}
