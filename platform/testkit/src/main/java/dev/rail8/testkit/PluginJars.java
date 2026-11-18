package dev.rail8.testkit;

import dev.rail8.api.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Writes a plugin jar for a test from classes the test compiled. A node, or the CLI, loads the jar as it loads any plugin: in a
 * class loader of its own, so the classes in it are copies, distinct from the test's own.
 *
 * @since 0.1
 */
public final class PluginJars {

    private PluginJars() {}

    /**
     * Writes {@code jar} with a manifest whose {@code Implementation-Version} is {@code version}; the service file
     * {@code META-INF/services/dev.rail8.api.plugin.Plugin} naming {@code providers}, one per line (no service file when there is
     * none); and the class file of each of {@code classes}, read from its own class loader. A nested class is a class of its own:
     * list it by itself.
     *
     * @param providers the binary names the service file lists; a name need not be one of {@code classes}
     * @return {@code jar}
     * @throws IllegalArgumentException a class whose class file its class loader does not have
     */
    public static Path write(Path jar, String version, List<String> providers, Class<?>... classes) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, version);
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file, manifest)) {
            if (!providers.isEmpty()) {
                out.putNextEntry(new JarEntry("META-INF/services/" + Plugin.class.getName()));
                out.write((String.join("\n", providers) + "\n").getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            for (Class<?> c : classes) {
                String entry = c.getName().replace('.', '/') + ".class";
                try (InputStream in = c.getClassLoader().getResourceAsStream(entry)) {
                    if (in == null) throw new IllegalArgumentException("the class loader of " + c.getName() + " has no " + entry);
                    out.putNextEntry(new JarEntry(entry));
                    in.transferTo(out);
                    out.closeEntry();
                }
            }
        }
        return jar;
    }
}
