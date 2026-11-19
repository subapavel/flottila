package dev.rail8.components.http;

import dev.rail8.api.Component;
import dev.rail8.api.plugin.ComponentContext;
import dev.rail8.api.plugin.HttpIngress;
import dev.rail8.api.plugin.Plugin;
import dev.rail8.api.plugin.PluginSettings;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** The http module: found on the class path as a {@link Plugin} named {@code http}, it brings the http component only. */
class HttpPluginTest {

    @Test
    void theModuleIsFoundOnTheClassPathAndBringsTheComponentOnly() {
        List<Plugin> found = ServiceLoader.load(Plugin.class).stream().map(ServiceLoader.Provider::get).toList();
        assertEquals(List.of(HttpPlugin.class), found.stream().map(Object::getClass).toList());
        Plugin module = found.getFirst();
        assertEquals("http", module.name());
        assertEquals(Plugin.API, module.api());
        FakeRuntime rt = FakeRuntime.create();
        Vertx vertx = Vertx.vertx();
        try {
            List<Component> components = module.components(new ComponentContext(vertx, rt.blobs(), rt.ids(), rt.timer(), rt.clock(), HttpIngress.NONE));
            assertEquals(1, components.size());
            assertInstanceOf(HttpComponent.class, components.getFirst());
            assertEquals("http", components.getFirst().scheme());
            assertEquals(Optional.of("/http"), components.getFirst().httpPrefix(), "its address on the node's HTTP port");
        } finally {
            vertx.close().await();
        }
        assertEquals(List.of(), module.steps(PluginSettings.of("http", Map.of())));
        assertEquals(List.of(), module.languages());
    }
}
