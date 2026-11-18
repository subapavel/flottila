package dev.rail8.runtime.plugin;

import dev.rail8.api.Component;
import dev.rail8.api.Expressions;
import dev.rail8.api.StepFactory;
import dev.rail8.api.plugin.ComponentContext;
import dev.rail8.api.plugin.Plugin;
import dev.rail8.core.flow.FlowBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The plugins a node loads from its plugin directory ({@value #ENV}), and what they add to what it ships. The node and the CLI
 * load them alike.
 *
 * <p>Each entry of the directory, in name order, is one plugin: a {@code .jar} file, or a directory whose {@code .jar} files
 * (not its subdirectories', and nothing else in it) are the plugin's. Each plugin gets a class loader of its own over its jars.
 * Its parent passes on the JDK and, from the node, the classes and resources of {@link #SHARED} only, never a service file; so
 * a plugin's jars name its one {@link Plugin} in {@code META-INF/services} themselves. Loading refuses, with an
 * {@link IllegalArgumentException} naming the entry: a directory that is none, cannot be read or holds more than
 * {@value #MAX_PLUGINS} entries; an entry that is neither a {@code .jar} nor a directory, or a directory without a jar or with more
 * than {@value #MAX_JARS}; jars that cannot be read; a plugin whose jars name no implementation, or more than one, or one that
 * cannot be built; a name that is null, no lower-case label, {@code rail8} or another plugin's; and a plugin built against an api
 * this node does not load ({@link #loads}).
 *
 * <p>{@link #components}, {@link #steps} and {@link #languages} add what the plugins bring after what the node ships, plugins in
 * entry order, each call into a plugin with the thread's context class loader set to the plugin's own for that call. A plugin
 * adds and never replaces: a scheme, step type or expression language that is already the node's or another plugin's is refused,
 * naming both owners, and so are a built-in step type ({@link FlowBuilder#BUILT_INS}) and the scheme {@code mock}. So are a call
 * that throws — a component's {@code settings()} is called once as it is loaded, so a description that cannot be built is one —,
 * a null scheme, step type or language, and more than {@link Plugin#MAX_CONTRIBUTIONS} of one kind.
 *
 * <p>{@link #close} closes the plugins' class loaders: call it once nothing of theirs runs any more, after the node and its
 * Vert.x have stopped.
 *
 * @since 0.1
 */
public final class Plugins implements AutoCloseable {

    /** The environment variable naming the plugin directory; unset or blank, a node loads no plugin. */
    public static final String ENV = "RAIL8_PLUGINS";
    /** The most entries, so plugins, the plugin directory may hold. */
    public static final int MAX_PLUGINS = 64;
    /** The most jars one plugin's directory may hold. */
    public static final int MAX_JARS = 512;
    /** The packages a plugin shares with the node, by name prefix: their classes and resources are the node's. */
    static final List<String> SHARED = List.of("dev.rail8.api.", "io.vertx.core.", "io.netty.", "org.slf4j.");

    private static final System.Logger LOG = System.getLogger(Plugins.class.getName());
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final Pattern API = Pattern.compile("(\\d{1,4})\\.(\\d{1,4})");
    /** Whose a contribution of the node's own is, in a refusal. */
    private static final String SHIPPED = "rail8";
    /** What a {@link LinkageError} in a plugin's call is most likely about. */
    private static final String SEES = "; a plugin sees the JDK, its own jars and the node's dev.rail8.api, io.vertx.core, io.netty and org.slf4j";

    /**
     * One loaded plugin.
     *
     * @param entry its entry's file name in the plugin directory
     * @param name what {@link Plugin#name()} answered
     * @param api what {@link Plugin#api()} answered
     * @param version the {@code Implementation-Version} of the jar its implementation comes from, {@code -} when none
     * @param sha256 the SHA-256, in hex, over the bytes of its jars in name order
     */
    private record Loaded(String entry, Plugin plugin, URLClassLoader loader, String name, String api, String version, String sha256) {}

    private final List<Loaded> plugins;

    private Plugins(List<Loaded> plugins) {
        this.plugins = List.copyOf(plugins);
    }

    /** No plugin: the merges hand back what the node ships. */
    public static Plugins none() {
        return new Plugins(List.of());
    }

    /** The plugins of the directory {@code env} names in {@value #ENV}; none when it is unset or blank. */
    public static Plugins fromEnv(Map<String, String> env) {
        String dir = env.get(ENV);
        return dir == null || dir.isBlank() ? none() : load(Path.of(dir));
    }

    /** The plugins of {@code dir}, each entry of it one (see the class comment). */
    public static Plugins load(Path dir) {
        if (!Files.isDirectory(dir)) throw new IllegalArgumentException(ENV + " names no directory: " + dir);
        List<Path> entries = list(dir, ENV + " " + dir);
        if (entries.size() > MAX_PLUGINS) {
            throw new IllegalArgumentException(ENV + " holds " + entries.size() + " entries; at most " + MAX_PLUGINS);
        }
        Gate gate = new Gate(Plugins.class.getClassLoader());
        List<Loaded> loaded = new ArrayList<>();
        try {
            Map<String, String> entryByName = new HashMap<>();
            for (Path entry : entries) {
                Loaded p = load(entry, gate);
                loaded.add(p);
                String other = entryByName.putIfAbsent(p.name(), p.entry());
                if (other != null) throw new IllegalArgumentException("plugin " + p.entry() + ": the name " + p.name() + " is " + other + "'s already");
            }
            return new Plugins(loaded);
        } catch (RuntimeException refused) {
            close(loaded);
            throw refused;
        }
    }

    /** The plugin of {@code entry}, its loader closed again when it is refused. */
    private static Loaded load(Path entry, Gate gate) {
        String at = entry.getFileName().toString();
        List<Path> jars = jars(entry, at);
        URLClassLoader loader = new URLClassLoader("rail8-plugin-" + at, urls(jars), gate);
        try {
            List<ServiceLoader.Provider<Plugin>> own;
            Plugin plugin;
            try {
                own = ServiceLoader.load(Plugin.class, loader).stream().filter(p -> p.type().getClassLoader() == loader).toList();
                if (own.size() != 1) {
                    throw new IllegalArgumentException("plugin " + at + ": its jars name " + own.size() + " implementations of " + Plugin.class.getName()
                            + " in META-INF/services; a plugin names exactly one");
                }
                plugin = withContext(loader, own.getFirst()::get);
            } catch (ServiceConfigurationError | LinkageError broken) {
                throw new IllegalArgumentException("plugin " + at + ": its implementation cannot be built: " + broken, broken);
            }
            String name = call("plugin " + at, loader, "name()", plugin::name, IllegalArgumentException::new);
            if (name == null || !NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("plugin " + at + ": the name '" + name
                        + "' is not a lower-case letter followed by at most 63 lower-case letters, digits or dashes");
            }
            if (SHIPPED.equals(name)) throw new IllegalArgumentException("plugin " + at + ": the name " + SHIPPED + " stands for what the node ships");
            String api = String.valueOf(call("plugin " + at, loader, "api()", plugin::api, IllegalArgumentException::new));
            if (!loads(Plugin.API, api)) {
                throw new IllegalArgumentException("plugin " + at + ": built against api " + api + ", which a node of api " + Plugin.API + " does not load");
            }
            String version = Optional.ofNullable(own.getFirst().type().getPackage()).map(Package::getImplementationVersion).orElse("-");
            return new Loaded(at, plugin, loader, name, api, version, sha256(jars, at));
        } catch (RuntimeException refused) {
            close(at, loader);
            throw refused;
        }
    }

    /**
     * Whether a node of api {@code node} loads a plugin built against {@code plugin}, both {@code major.minor}: while the node's
     * major is 0, the same major.minor only; from 1 on, the same major and a minor up to the node's.
     */
    static boolean loads(String node, String plugin) {
        var n = API.matcher(node);
        var p = API.matcher(plugin);
        if (!n.matches() || !p.matches()) return false;
        int major = Integer.parseInt(n.group(1));
        int minor = Integer.parseInt(n.group(2));
        int pluginMajor = Integer.parseInt(p.group(1));
        int pluginMinor = Integer.parseInt(p.group(2));
        return major == 0 ? pluginMajor == 0 && pluginMinor == minor : pluginMajor == major && pluginMinor <= minor;
    }

    /**
     * {@code shipped}'s components, then each plugin's, built over the context the returned function is given; the function
     * throws an {@link IllegalStateException} for a refusal (see the class comment), as {@code Node.start} does for two
     * components of one scheme.
     */
    public Function<ComponentContext, List<Component>> components(Function<ComponentContext, List<Component>> shipped) {
        Objects.requireNonNull(shipped, "shipped");
        return context -> merge(shipped.apply(context), "components()", p -> p.components(context), Component::scheme, "scheme",
                Map.of("mock", "is the platform's mock"), "settings()", Component::settings, IllegalStateException::new);
    }

    /** {@code shipped}, then each plugin's step factories; an {@link IllegalArgumentException} for a refusal (see the class comment). */
    public List<StepFactory> steps(List<StepFactory> shipped) {
        Map<String, String> builtIn = new HashMap<>();
        FlowBuilder.BUILT_INS.forEach(type -> builtIn.put(type, "is built in"));
        return merge(shipped, "steps()", Plugin::steps, StepFactory::type, "step type", builtIn, null, null, IllegalArgumentException::new);
    }

    /** {@code shipped}, then each plugin's expression languages; an {@link IllegalArgumentException} for a refusal (see the class comment). */
    public List<Expressions> languages(List<Expressions> shipped) {
        return merge(shipped, "languages()", Plugin::languages, Expressions::language, "expression language", Map.of(), null, null,
                IllegalArgumentException::new);
    }

    /**
     * One line per plugin, in entry order, for the node's log: {@code plugin <name> <version> (api <api>, sha256 <hex>) from <entry>}.
     */
    public List<String> describe() {
        return plugins.stream().map(p -> "plugin " + p.name() + " " + p.version() + " (api " + p.api() + ", sha256 " + p.sha256() + ") from " + p.entry())
                .toList();
    }

    /** Closes every plugin's class loader; one that fails to close is logged and the others are still closed. */
    @Override
    public void close() {
        close(plugins);
    }

    private static void close(List<Loaded> loaded) {
        loaded.forEach(p -> close(p.entry(), p.loader()));
    }

    private static void close(String entry, URLClassLoader loader) {
        try {
            loader.close();
        } catch (IOException failed) {
            LOG.log(System.Logger.Level.WARNING, "closing the class loader of plugin " + entry + " failed", failed);
        }
    }

    /**
     * {@code shipped}, then what each plugin's {@code method} ({@code contributions}) brings, keyed by {@code key}: a key that is
     * {@code reserved} is refused with the reason it maps to, and one that is already taken names its owner. When {@code check}
     * is not null it is called on each contribution, as {@code checked}: what it throws refuses the plugin.
     */
    private <T> List<T> merge(List<T> shipped, String method, Function<Plugin, List<T>> contributions, Function<T, String> key, String keyName,
                              Map<String, String> reserved, String checked, Function<T, ?> check, BiFunction<String, Throwable, RuntimeException> refusal) {
        List<T> all = new ArrayList<>(shipped);
        Map<String, String> owners = new HashMap<>();
        shipped.forEach(t -> owners.putIfAbsent(key.apply(t), SHIPPED));
        for (Loaded p : plugins) {
            String who = "plugin " + p.name();
            List<T> brought = call(who, p.loader(), method, () -> List.copyOf(contributions.apply(p.plugin())), refusal);
            if (brought.size() > Plugin.MAX_CONTRIBUTIONS) {
                throw refusal.apply("plugin " + p.name() + ": " + method + " brings " + brought.size() + "; at most " + Plugin.MAX_CONTRIBUTIONS, null);
            }
            for (T t : brought) {
                String k = call(who, p.loader(), keyName + " of " + t.getClass().getName(), () -> key.apply(t), refusal);
                if (k == null) throw refusal.apply(who + ": the " + keyName + " of " + t.getClass().getName() + " is null", null);
                if (reserved.containsKey(k)) throw refusal.apply("plugin " + p.name() + ": the " + keyName + " " + k + " " + reserved.get(k), null);
                String owner = owners.putIfAbsent(k, "plugin " + p.name());
                if (owner != null) {
                    throw refusal.apply("plugin " + p.name() + ": the " + keyName + " " + k + " is " + owner + "'s already; a plugin adds and never replaces", null);
                }
                if (check != null) call(who, p.loader(), checked + " of " + t.getClass().getName(), () -> check.apply(t), refusal);
                all.add(t);
            }
        }
        return List.copyOf(all);
    }

    /**
     * {@code body}, a call into a plugin's {@code method} with the context class loader {@code loader}; what it throws, as a
     * {@code refusal} whose message starts with {@code who}.
     */
    private static <T> T call(String who, ClassLoader loader, String method, Supplier<T> body, BiFunction<String, Throwable, RuntimeException> refusal) {
        try {
            return withContext(loader, body);
        } catch (RuntimeException | LinkageError failed) {
            throw refusal.apply(who + ": " + method + " failed: " + failed + (failed instanceof LinkageError ? SEES : ""), failed);
        }
    }

    /** {@code body} with the thread's context class loader set to {@code loader}, the caller's back afterwards. */
    private static <T> T withContext(ClassLoader loader, Supplier<T> body) {
        Thread thread = Thread.currentThread();
        ClassLoader before = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return body.get();
        } finally {
            thread.setContextClassLoader(before);
        }
    }

    /** The entries of {@code dir}, in name order; a directory that cannot be read is refused, its message starting with {@code what}. */
    private static List<Path> list(Path dir, String what) {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.sorted().toList();
        } catch (IOException | UncheckedIOException failed) {
            throw new IllegalArgumentException(what + " cannot be read: " + failed, failed);
        }
    }

    /** The jars of the plugin {@code entry}: itself, or the {@code .jar} files of its directory, in name order. */
    private static List<Path> jars(Path entry, String at) {
        if (Files.isRegularFile(entry) && at.endsWith(".jar")) return List.of(entry);
        if (!Files.isDirectory(entry)) throw new IllegalArgumentException("plugin " + at + ": an entry of " + ENV + " is a .jar or a directory of jars");
        List<Path> jars = list(entry, "plugin " + at + ": its directory").stream().filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar")).toList();
        if (jars.isEmpty()) throw new IllegalArgumentException("plugin " + at + ": the directory holds no .jar");
        if (jars.size() > MAX_JARS) throw new IllegalArgumentException("plugin " + at + ": the directory holds " + jars.size() + " jars; at most " + MAX_JARS);
        return jars;
    }

    private static URL[] urls(List<Path> jars) {
        URL[] urls = new URL[jars.size()];
        for (int i = 0; i < urls.length; i++) {
            try {
                urls[i] = jars.get(i).toUri().toURL();
            } catch (MalformedURLException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        return urls;
    }

    /** The SHA-256 over {@code jars}; jars that cannot be read are refused, naming the plugin of entry {@code at}. */
    private static String sha256(List<Path> jars, String at) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path jar : jars) {
                try (InputStream in = new DigestInputStream(Files.newInputStream(jar), digest)) {
                    in.transferTo(OutputStream.nullOutputStream());
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        } catch (IOException failed) {
            throw new IllegalArgumentException("plugin " + at + ": its jars cannot be read: " + failed, failed);
        }
    }

    /**
     * The parent of every plugin's class loader: the JDK (the platform class loader) and, from the node's class loader, the
     * classes and resources of {@link #SHARED} only. A service file is no shared resource, so a plugin's loader lists the
     * providers of its own jars.
     */
    static final class Gate extends ClassLoader {
        static {
            registerAsParallelCapable();
        }

        private final ClassLoader node;

        Gate(ClassLoader node) {
            super("rail8-plugin-gate", ClassLoader.getPlatformClassLoader());
            this.node = node;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (SHARED.stream().anyMatch(name::startsWith)) return node.loadClass(name);
            throw new ClassNotFoundException(name);
        }

        @Override
        protected URL findResource(String name) {
            return shared(name) ? node.getResource(name) : null;
        }

        @Override
        protected Enumeration<URL> findResources(String name) throws IOException {
            return shared(name) ? node.getResources(name) : Collections.emptyEnumeration();
        }

        private static boolean shared(String resource) {
            return SHARED.stream().anyMatch(p -> resource.startsWith(p.replace('.', '/')));
        }
    }
}
