package dev.rail8.steps.mapping;

import dev.rail8.api.StepFactory;
import dev.rail8.api.plugin.AdminService;
import dev.rail8.api.plugin.Plugin;
import dev.rail8.api.plugin.PluginSettings;
import dev.rail8.mapping.studio.Studio;

import java.util.List;

/**
 * The mapping module: the {@code mapping} step, and the mapping studio's services of the admin API, one for each operation of
 * the studio: {@code GET catalogue}, and {@code POST canonical}, {@code create}, {@code structure}, {@code compile},
 * {@code test} and {@code refresh}.
 *
 * @since 0.1
 */
public final class MappingPlugin implements Plugin {

    @Override public String name() { return "mapping"; }

    @Override public String api() { return API; }

    /** The mapping step. */
    @Override public List<StepFactory> steps() { return List.of(new MappingStepFactory()); }

    /** The studio's services, one for each of its operations; they read no settings. */
    @Override
    public List<AdminService> services(PluginSettings settings) {
        return Studio.OPERATIONS.stream().<AdminService>map(StudioService::new).toList();
    }
}
