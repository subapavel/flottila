package dev.rail8.components.direct;

import dev.rail8.api.Component;
import dev.rail8.api.plugin.ComponentContext;
import dev.rail8.api.plugin.Plugin;
import dev.rail8.api.plugin.PluginSettings;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** The direct module: found on the class path as a {@link Plugin} named {@code direct}, it brings the direct component only. */
class DirectPluginTest {

    @Test
    void theModuleIsFoundOnTheClassPathAndBringsTheComponentOnly() {
        List<Plugin> found = ServiceLoader.load(Plugin.class).stream().map(ServiceLoader.Provider::get).toList();
        assertEquals(List.of(DirectPlugin.class), found.stream().map(Object::getClass).toList());
        Plugin module = found.getFirst();
        assertEquals("direct", module.name());
        assertEquals(Plugin.API, module.api());
        FakeRuntime rt = FakeRuntime.create();
        Vertx vertx = Vertx.vertx();
        try {
            List<Component> components = module.components(new ComponentContext(vertx, rt.blobs(), rt.ids(), rt.timer(), rt.clock()));
            assertEquals(1, components.size());
            assertInstanceOf(DirectComponent.class, components.getFirst());
            assertEquals("direct", components.getFirst().scheme());
        } finally {
            vertx.close().await();
        }
        assertEquals(List.of(), module.steps(PluginSettings.of("direct", Map.of())));
        assertEquals(List.of(), module.languages());
    }
}
