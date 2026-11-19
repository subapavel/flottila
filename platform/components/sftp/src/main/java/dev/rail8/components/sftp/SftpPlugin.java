package dev.rail8.components.sftp;

import dev.rail8.api.Component;
import dev.rail8.api.plugin.ComponentContext;
import dev.rail8.api.plugin.Plugin;

import java.util.List;

/**
 * The sftp module: the sftp component, scheme {@code sftp}.
 *
 * @since 0.1
 */
public final class SftpPlugin implements Plugin {

    @Override public String name() { return "sftp"; }

    @Override public String api() { return API; }

    /** The sftp component, built over the node's {@code context}. */
    @Override
    public List<Component> components(ComponentContext context) {
        return List.of(new SftpComponent(context.blobs(), context.ids(), context.timer(), context.clock()));
    }
}
