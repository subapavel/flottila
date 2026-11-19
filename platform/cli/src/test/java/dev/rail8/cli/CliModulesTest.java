package dev.rail8.cli;

import dev.rail8.runtime.plugin.Plugins;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The CLI runs over a node's modules: it finds those of its class path the way a node does, and they are the seven that ship
 * with the platform, each the same module, version and jar a node lists.
 */
class CliModulesTest {

    @Test
    void theCliFindsTheModulesANodeFinds() {
        try (Plugins cli = Cli.plugins(Optional.empty()); Plugins node = Plugins.fromEnv(Map.of())) {
            assertEquals(node.describe(), cli.describe());
            assertEquals(List.of("direct", "http", "jq", "mapping", "sftp", "xsd", "xslt"),
                    cli.describe().stream().map(line -> line.split(" ")[1]).toList(), String.join("\n", cli.describe()));
        }
    }
}
