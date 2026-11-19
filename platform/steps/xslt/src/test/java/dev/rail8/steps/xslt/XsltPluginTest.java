package dev.rail8.steps.xslt;

import dev.rail8.api.Expressions;
import dev.rail8.api.StepFactory;
import dev.rail8.api.plugin.Plugin;
import dev.rail8.api.plugin.PluginSettings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The xslt module: found on the class path as a {@link Plugin} named {@code xslt}, with its steps and the language xpath. */
class XsltPluginTest {

    @Test
    void theModuleIsFoundOnTheClassPathWithItsStepsAndLanguages() {
        List<Plugin> found = ServiceLoader.load(Plugin.class).stream().map(ServiceLoader.Provider::get).toList();
        assertEquals(List.of(XsltPlugin.class), found.stream().map(Object::getClass).toList());
        Plugin module = found.getFirst();
        assertEquals("xslt", module.name());
        assertEquals(Plugin.API, module.api());
        assertEquals(types(new SaxonXml().steps()), types(module.steps(PluginSettings.of("xslt", Map.of()))));
        assertEquals(List.of("xpath"), module.languages().stream().map(Expressions::language).toList());
        assertEquals(List.of(), module.components(null));
    }

    private static List<String> types(List<StepFactory> steps) {
        return steps.stream().map(StepFactory::type).toList();
    }
}
