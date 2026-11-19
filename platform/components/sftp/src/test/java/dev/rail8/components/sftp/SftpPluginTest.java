package dev.rail8.components.sftp;

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
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** The sftp module: found on the class path as a {@link Plugin} named {@code sftp}, it brings the sftp component only. */
class SftpPluginTest {

    @Test
    void theModuleIsFoundOnTheClassPathAndBringsTheComponentOnly() {
        List<Plugin> found = ServiceLoader.load(Plugin.class).stream().map(ServiceLoader.Provider::get).toList();
        assertEquals(List.of(SftpPlugin.class), found.stream().map(Object::getClass).toList());
        Plugin module = found.getFirst();
        assertEquals("sftp", module.name());
        assertEquals(Plugin.API, module.api());
        FakeRuntime rt = FakeRuntime.create();
        Vertx vertx = Vertx.vertx();
        try {
            List<Component> components = module.components(new ComponentContext(vertx, rt.blobs(), rt.ids(), rt.timer(), rt.clock(), HttpIngress.NONE));
            assertEquals(1, components.size());
            assertInstanceOf(SftpComponent.class, components.getFirst());
            assertEquals("sftp", components.getFirst().scheme());
        } finally {
            vertx.close().await();
        }
        assertEquals(List.of(), module.steps(PluginSettings.of("sftp", Map.of())));
        assertEquals(List.of(), module.languages());
    }
}
