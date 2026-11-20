package dev.rail8.runtime.plugin;

import dev.rail8.api.Component;
import dev.rail8.api.Expressions;
import dev.rail8.api.StepFactory;
import dev.rail8.api.plugin.AdminService;
import dev.rail8.api.plugin.ComponentContext;
import dev.rail8.api.plugin.Plugin;
import dev.rail8.api.plugin.PluginSettings;
import dev.rail8.core.flow.FlowBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The plugins of a node: its modules, found on its class path, and the plugins of its plugin directory ({@value #ENV}); what
 * they add is all the components, step factories and expression languages the node has, the built-in steps and the mock aside,
 * and the services of its admin API that its modules bring. The node and the CLI load them alike; only the node takes the
 * services ({@link #services}).
 *
 * <p>A module is a {@link Plugin} that a jar on the class path names in {@code META-INF/services}: the node's own build ships
 * one per component and step (http, sftp, direct, xslt with xpath, xsd, jq, mapping). Modules share the node's class loader. At
 * most {@value #MAX_MODULES} are found; they come in name order.
 *
 * <p>Each entry of the plugin directory, in name order, is one plugin: a {@code .jar} file, or a directory whose {@code .jar}
 * files (not its subdirectories', and nothing else in it) are the plugin's. Each plugin gets a class loader of its own over its
 * jars. Its parent passes on the JDK and, from the node, the classes and resources of {@link #SHARED} only, never a service
 * file; so a plugin's jars name its one {@link Plugin} in {@code META-INF/services} themselves. The plugins come after the
 * modules.
 *
 * <p>Loading refuses, with an {@link IllegalArgumentException} naming the module or entry: more than {@value #MAX_MODULES}
 * modules; a directory that is none, cannot be read or holds more than {@value #MAX_PLUGINS} entries; an entry that is neither a
 * {@code .jar} nor a directory, or a directory without a jar or with more than {@value #MAX_JARS}; jars that cannot be read; a
 * plugin whose jars name no implementation, or more than one, or one that cannot be built; a name that is null, no lower-case
 * label, {@code rail8} or another module's or plugin's; and a module or plugin built against an api this node does not load
 * ({@link #loads}). Loading takes each one's step factories, given its settings ({@link PluginSettings#of}), and its expression
 * languages, so a refusal of those is one too.
 *
 * <p>A module or plugin adds and never replaces: a scheme, step type or expression language that is already another's is
 * refused, naming both owners, and so are a built-in step type ({@link FlowBuilder#BUILT_INS}) and the scheme {@code mock}. So
 * are a call that throws — a component's {@code settings()} is called once as it is built, so a description that cannot be built
 * is one —, a null scheme, step type, language or {@code settings()}, and more than {@link Plugin#MAX_CONTRIBUTIONS} of one kind.
 * Each call into a module or plugin runs with the thread's context class loader set to its own for that call.
 *
 * <p>{@link #describe} gives each module and plugin as a line of the node's log, and {@link #list} gives each one with what it
 * brings, as the admin API lists them; both from the same values.
 *
 * <p>{@link #close} closes the plugins' class loaders: call it once nothing of theirs runs any more, after the node and its
 * Vert.x have stopped.
 *
 * @since 0.1
 */
public final class Plugins implements AutoCloseable {

    /** The environment variable naming the plugin directory; unset or blank, a node loads no plugin of a directory. */
    public static final String ENV = "RAIL8_PLUGINS";
    /** The most modules the class path may name. */
    public static final int MAX_MODULES = 64;
    /** The most entries, so plugins, the plugin directory may hold. */
    public static final int MAX_PLUGINS = 64;
    /** The most jars one plugin's directory may hold. */
    public static final int MAX_JARS = 512;
    /** The packages a plugin shares with the node, by name prefix: their classes and resources are the node's. */
    static final List<String> SHARED = List.of("dev.rail8.api.", "io.vertx.core.", "io.netty.", "org.slf4j.");

    private static final System.Logger LOG = System.getLogger(Plugins.class.getName());
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final Pattern API = Pattern.compile("(\\d{1,4})\\.(\\d{1,4})");
    private static final Pattern SERVICE_PATH = Pattern.compile("[a-z0-9-]{1,32}(/[a-z0-9-]{1,32}){0,3}");
    /** The name that stands for the platform itself, its built-in steps and its mock: no module's or plugin's. */
    private static final String PLATFORM = "rail8";
    /** What a {@link LinkageError} in a plugin's call is most likely about. */
    private static final String SEES = "; a plugin sees the JDK, its own jars and the node's dev.rail8.api, io.vertx.core, io.netty and org.slf4j";

    /**
     * One loaded module or plugin.
     *
     * @param kind    {@code module} or {@code plugin}
     * @param where   where it was found, as a refusal while loading names it: {@code module <its class>} or {@code plugin <its entry>}
     * @param loader  the class loader its calls run with: the one of its class
     * @param own     a plugin's class loader of its own, closed with it; none for a module
     * @param name    what {@link Plugin#name()} answered
     * @param api     what {@link Plugin#api()} answered
     * @param version the {@code Implementation-Version} of the jar its implementation comes from; empty when none
     * @param sha256  the SHA-256, in hex, over the bytes of its jars in name order; empty for a module of a directory
     * @param from    where it came from, in its log line: a module's jar or directory, a plugin's entry
     */
    private record Loaded(String kind, String where, Plugin plugin, ClassLoader loader, Optional<URLClassLoader> own, String name, String api,
                          Optional<String> version, Optional<String> sha256, String from) {
        /** Whose a contribution of it is, in a refusal: {@code module <name>} or {@code plugin <name>}. */
        String who() {
            return kind + " " + name;
        }
    }

    /** A name and an api that passed their checks. */
    private record Checked(String name, String api) {}

    /**
     * One module or plugin as the node lists it: who it is and what it brings.
     *
     * @param kind      {@code module} or {@code plugin}
     * @param name      its name
     * @param version   the {@code Implementation-Version} of the jar its implementation comes from; empty when none
     * @param api       the api version it was built against
     * @param sha256    the SHA-256, in hex, over the bytes of its jars in name order; empty for a module of a directory
     * @param from      where it came from: a module's jar or directory, a plugin's entry of the plugin directory
     * @param schemes   the schemes of its components, sorted; none until the node has built every component
     *                  ({@link #components(ComponentContext)})
     * @param steps     the types of its step factories, sorted
     * @param languages the names of its expression languages, sorted
     */
    public record Listed(String kind, String name, Optional<String> version, String api, Optional<String> sha256, String from, List<String> schemes,
                         List<String> steps, List<String> languages) {
        public Listed {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(api, "api");
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(from, "from");
            schemes = List.copyOf(schemes);
            steps = List.copyOf(steps);
            languages = List.copyOf(languages);
        }
    }

    private final List<Loaded> loaded;
    private final List<StepFactory> steps;
    private final List<Expressions> languages;
    /** The step types and the languages each one brings, by its name, sorted. */
    private final Map<String, List<String>> stepTypes;
    private final Map<String, List<String>> languageNames;
    /** The schemes each one's components have, by its name, sorted: recorded when every component was built. */
    private volatile Map<String, List<String>> schemes = Map.of();

    private Plugins(List<Loaded> loaded, List<StepFactory> steps, Map<String, List<String>> stepTypes, List<Expressions> languages,
                    Map<String, List<String>> languageNames) {
        this.loaded = List.copyOf(loaded);
        this.steps = List.copyOf(steps);
        this.stepTypes = Map.copyOf(stepTypes);
        this.languages = List.copyOf(languages);
        this.languageNames = Map.copyOf(languageNames);
    }

    /**
     * The node's: the modules of the class path {@link Plugins} is loaded from, then the plugins of the directory {@code env}
     * names in {@value #ENV} (none when it is unset or blank), each one's settings read from {@code env}.
     */
    public static Plugins fromEnv(Map<String, String> env) {
        return load(Plugins.class.getClassLoader(), dir(env), env);
    }

    /** The plugin directory {@code env} names in {@value #ENV}; empty when the variable is unset or blank. */
    public static Optional<Path> dir(Map<String, String> env) {
        return Optional.ofNullable(env.get(ENV)).filter(dir -> !dir.isBlank()).map(Path::of);
    }

    /**
     * The modules {@code classPath} names, in name order, then the plugins of {@code dir}, each entry of it one, in entry order
     * (see the class comment); their step factories and expression languages taken.
     *
     * @param classPath the class loader whose service files name the modules: the node's own
     * @param dir       the plugin directory; empty for none
     * @param env       the variables each one's settings are read from ({@link PluginSettings#of}): a node's environment, or
     *                  empty for none, as {@code rail8 validate} and {@code rail8 test} pass
     */
    public static Plugins load(ClassLoader classPath, Optional<Path> dir, Map<String, String> env) {
        Objects.requireNonNull(classPath, "classPath");
        Objects.requireNonNull(env, "env");
        List<Loaded> loaded = new ArrayList<>();
        try {
            loaded.addAll(modules(classPath));
            if (dir.isPresent()) plugins(dir.get(), loaded);
            Map<String, String> whereByName = new HashMap<>();
            for (Loaded l : loaded) {
                String other = whereByName.putIfAbsent(l.name(), l.where());
                if (other != null) throw new IllegalArgumentException(l.where() + ": the name " + l.name() + " is " + other + "'s already");
            }
            Map<String, String> builtIn = new HashMap<>();
            FlowBuilder.BUILT_INS.forEach(type -> builtIn.put(type, "is built in"));
            Map<String, List<String>> stepTypes = new HashMap<>();
            List<StepFactory> steps = merge(loaded, "steps()", l -> l.plugin().steps(PluginSettings.of(l.name(), env)), StepFactory::type,
                    "step type", builtIn, null, null, IllegalArgumentException::new, stepTypes);
            Map<String, List<String>> languageNames = new HashMap<>();
            List<Expressions> languages = merge(loaded, "languages()", l -> l.plugin().languages(), Expressions::language, "expression language",
                    Map.of(), null, null, IllegalArgumentException::new, languageNames);
            return new Plugins(loaded, steps, stepTypes, languages, languageNames);
        } catch (RuntimeException refused) {
            close(loaded);
            throw refused;
        }
    }

    /** The modules {@code classPath} names, in name order. */
    private static List<Loaded> modules(ClassLoader classPath) {
        List<ServiceLoader.Provider<Plugin>> providers;
        try {
            providers = ServiceLoader.load(Plugin.class, classPath).stream().limit(MAX_MODULES + 1L).toList();
        } catch (ServiceConfigurationError | LinkageError broken) {
            throw new IllegalArgumentException("the class path names a module that cannot be found: " + broken, broken);
        }
        if (providers.size() > MAX_MODULES) throw new IllegalArgumentException("the class path names more than " + MAX_MODULES + " modules");
        List<Loaded> modules = new ArrayList<>();
        for (ServiceLoader.Provider<Plugin> provider : providers) modules.add(module(provider));
        modules.sort(Comparator.comparing(Loaded::name));
        return modules;
    }

    /** The module of {@code provider}, a class of the class path. */
    private static Loaded module(ServiceLoader.Provider<Plugin> provider) {
        Class<? extends Plugin> type = provider.type();
        String where = "module " + type.getName();
        ClassLoader loader = type.getClassLoader();
        Plugin plugin;
        try {
            plugin = withContext(loader, provider::get);
        } catch (ServiceConfigurationError | LinkageError broken) {
            throw new IllegalArgumentException(where + ": its implementation cannot be built: " + broken, broken);
        }
        Checked checked = checked(where, plugin, loader);
        Optional<Path> location = location(type);
        String from = location.map(l -> Files.isRegularFile(l) ? l.getFileName().toString() : l.toString()).orElse("the class path");
        Optional<String> sha256 = location.filter(Files::isRegularFile).map(jar -> sha256(List.of(jar), where));
        return new Loaded("module", where, plugin, loader, Optional.empty(), checked.name(), checked.api(), version(type), sha256, from);
    }

    /** The jar, or the directory of classes, {@code type} was loaded from; empty when that is no file. */
    private static Optional<Path> location(Class<?> type) {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        URL url = source == null ? null : source.getLocation();
        if (url == null || !"file".equals(url.getProtocol())) return Optional.empty();
        try {
            return Optional.of(Path.of(url.toURI()));
        } catch (URISyntaxException | IllegalArgumentException noPath) {
            return Optional.empty();
        }
    }

    /** The plugins of {@code dir}, each entry of it one, added to {@code into} as each loads, so a refusal closes those before it. */
    private static void plugins(Path dir, List<Loaded> into) {
        if (!Files.isDirectory(dir)) throw new IllegalArgumentException(ENV + " names no directory: " + dir);
        List<Path> entries = list(dir, ENV + " " + dir);
        if (entries.size() > MAX_PLUGINS) {
            throw new IllegalArgumentException(ENV + " holds " + entries.size() + " entries; at most " + MAX_PLUGINS);
        }
        Gate gate = new Gate(Plugins.class.getClassLoader());
        for (Path entry : entries) into.add(plugin(entry, gate));
    }

    /** The plugin of {@code entry}, its loader closed again when it is refused. */
    private static Loaded plugin(Path entry, Gate gate) {
        String at = entry.getFileName().toString();
        String where = "plugin " + at;
        List<Path> jars = jars(entry, at);
        URLClassLoader loader = new URLClassLoader("rail8-plugin-" + at, urls(jars), gate);
        try {
            List<ServiceLoader.Provider<Plugin>> own;
            Plugin plugin;
            try {
                own = ServiceLoader.load(Plugin.class, loader).stream().filter(p -> p.type().getClassLoader() == loader).toList();
                if (own.size() != 1) {
                    throw new IllegalArgumentException(where + ": its jars name " + own.size() + " implementations of " + Plugin.class.getName()
                            + " in META-INF/services; a plugin names exactly one");
                }
                plugin = withContext(loader, own.getFirst()::get);
            } catch (ServiceConfigurationError | LinkageError broken) {
                throw new IllegalArgumentException(where + ": its implementation cannot be built: " + broken, broken);
            }
            Checked checked = checked(where, plugin, loader);
            return new Loaded("plugin", where, plugin, loader, Optional.of(loader), checked.name(), checked.api(), version(own.getFirst().type()),
                    Optional.of(sha256(jars, where)), at);
        } catch (RuntimeException refused) {
            close(at, loader);
            throw refused;
        }
    }

    /** The name and api of {@code plugin}, found at {@code where}, once they pass: a lower-case label, not {@code rail8}, an api this node loads. */
    private static Checked checked(String where, Plugin plugin, ClassLoader loader) {
        String name = call(where, loader, "name()", plugin::name, IllegalArgumentException::new);
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(where + ": the name '" + name
                    + "' is not a lower-case letter followed by at most 63 lower-case letters, digits or dashes");
        }
        if (PLATFORM.equals(name)) throw new IllegalArgumentException(where + ": the name " + PLATFORM + " stands for the platform itself");
        String api = String.valueOf(call(where, loader, "api()", plugin::api, IllegalArgumentException::new));
        if (!loads(Plugin.API, api)) {
            throw new IllegalArgumentException(where + ": built against api " + api + ", which a node of api " + Plugin.API + " does not load");
        }
        return new Checked(name, api);
    }

    /** The {@code Implementation-Version} of the jar {@code type} comes from; empty when none. */
    private static Optional<String> version(Class<?> type) {
        return Optional.ofNullable(type.getPackage()).map(Package::getImplementationVersion);
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
     * Each module's components, then each plugin's, built over {@code context}; an {@link IllegalStateException} for a refusal
     * (see the class comment), as {@code Node.start} does for two components of one scheme. Their schemes are what
     * {@link #list()} lists from then on.
     */
    public List<Component> components(ComponentContext context) {
        Objects.requireNonNull(context, "context");
        Map<String, List<String>> owned = new HashMap<>();
        List<Component> all = components(loaded, context, owned);
        schemes = Map.copyOf(owned);
        return all;
    }

    /**
     * The components of the module or plugin named {@code name} alone, built over {@code context} and checked as
     * {@link #components(ComponentContext)} checks them; none when no module or plugin has that name. For a caller that built
     * every component once and needs one module's again over other fakes, as {@code rail8 test} needs the direct module's for each
     * case.
     */
    public List<Component> components(String name, ComponentContext context) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(context, "context");
        return components(loaded.stream().filter(l -> l.name().equals(name)).toList(), context, new HashMap<>());
    }

    private static List<Component> components(List<Loaded> loaded, ComponentContext context, Map<String, List<String>> owned) {
        return merge(loaded, "components()", l -> l.plugin().components(context), Component::scheme, "scheme",
                Map.of("mock", "is the platform's mock"), "settings()", Component::settings, IllegalStateException::new, owned);
    }

    /**
     * Each module's services of the admin API, by its name, each module's built over its settings read from {@code env}
     * ({@link PluginSettings#of}); a module that brings none is not listed. The node calls it once, at its start; {@code rail8
     * validate} and {@code rail8 test} never do. Refused, with an {@link IllegalArgumentException} naming the module or plugin:
     * a plugin of the plugin directory that brings one, a call that throws or a null service, more than
     * {@link Plugin#MAX_CONTRIBUTIONS}, a method other than {@code GET} or {@code POST}, a path outside the rule of
     * {@link AdminService#path()} ({@link #servicePath}), and two services of one module with the same method and path.
     */
    public Map<String, List<AdminService>> services(Map<String, String> env) {
        Objects.requireNonNull(env, "env");
        Map<String, List<AdminService>> all = new LinkedHashMap<>();
        for (Loaded l : loaded) {
            String who = l.who();
            List<AdminService> brought = call(who, l.loader(), "services()", () -> List.copyOf(l.plugin().services(PluginSettings.of(l.name(), env))),
                    IllegalArgumentException::new);
            if (brought.isEmpty()) continue;
            if (l.own().isPresent()) {
                throw new IllegalArgumentException(who + ": services() brings " + brought.size() + " admin services; only a module brings them, no plugin of " + ENV);
            }
            if (brought.size() > Plugin.MAX_CONTRIBUTIONS) {
                throw new IllegalArgumentException(who + ": services() brings " + brought.size() + "; at most " + Plugin.MAX_CONTRIBUTIONS);
            }
            Set<String> routes = new HashSet<>();
            for (AdminService service : brought) {
                String type = service.getClass().getName();
                String method = call(who, l.loader(), "method() of " + type, service::method, IllegalArgumentException::new);
                String path = call(who, l.loader(), "path() of " + type, service::path, IllegalArgumentException::new);
                if (!"GET".equals(method) && !"POST".equals(method)) {
                    throw new IllegalArgumentException(who + ": the method of " + type + " is '" + method + "'; an admin service is GET or POST");
                }
                if (!servicePath(path)) {
                    throw new IllegalArgumentException(who + ": the path of " + type + " is '" + path
                            + "'; an admin service's path is 1 to 4 segments of [a-z0-9-]{1,32}, joined by slashes");
                }
                if (!routes.add(method + " " + path)) throw new IllegalArgumentException(who + ": two admin services are " + method + " " + path);
            }
            all.put(l.name(), brought);
        }
        return Collections.unmodifiableMap(all);
    }

    /** Whether {@code path} is the path of an admin service: 1 to 4 segments of {@code [a-z0-9-]{1,32}}, joined by single slashes. */
    static boolean servicePath(String path) {
        return path != null && SERVICE_PATH.matcher(path).matches();
    }

    /** Each module's step factories, then each plugin's, as loading took them. */
    public List<StepFactory> steps() {
        return steps;
    }

    /** Each module's expression languages, then each plugin's, as loading took them. */
    public List<Expressions> languages() {
        return languages;
    }

    /**
     * One line per module, then per plugin, for the node's log: {@code module <name> <version> (api <api>, sha256 <hex>) from
     * <jar>}, {@code plugin <name> <version> (api <api>, sha256 <hex>) from <entry>}; {@code -} for a version or digest it has not.
     */
    public List<String> describe() {
        return loaded.stream().map(l -> l.who() + " " + l.version().orElse("-") + " (api " + l.api() + ", sha256 " + l.sha256().orElse("-")
                + ") from " + l.from()).toList();
    }

    /** Each module, then each plugin, with what it brings, in the order of {@link #describe()}. */
    public List<Listed> list() {
        Map<String, List<String>> built = schemes;
        return loaded.stream().map(l -> new Listed(l.kind(), l.name(), l.version(), l.api(), l.sha256(), l.from(), built.getOrDefault(l.name(), List.of()),
                stepTypes.getOrDefault(l.name(), List.of()), languageNames.getOrDefault(l.name(), List.of()))).toList();
    }

    /** Closes every plugin's class loader; one that fails to close is logged and the others are still closed. */
    @Override
    public void close() {
        close(loaded);
    }

    private static void close(List<Loaded> loaded) {
        loaded.forEach(l -> l.own().ifPresent(own -> close(l.from(), own)));
    }

    private static void close(String entry, URLClassLoader loader) {
        try {
            loader.close();
        } catch (IOException failed) {
            LOG.log(System.Logger.Level.WARNING, "closing the class loader of plugin " + entry + " failed", failed);
        }
    }

    /**
     * What each of {@code loaded} brings by {@code method} ({@code contributions}), keyed by {@code key}: a key that is
     * {@code reserved} is refused with the reason it maps to, and one that is already taken names its owner. When {@code check}
     * is not null it is called on each contribution, as {@code checked}: what it throws, or a null it returns, refuses it. The
     * keys of each one that brings any go into {@code owned}, by its name, sorted.
     */
    private static <T> List<T> merge(List<Loaded> loaded, String method, Function<Loaded, List<T>> contributions, Function<T, String> key, String keyName,
                                     Map<String, String> reserved, String checked, Function<T, ?> check, BiFunction<String, Throwable, RuntimeException> refusal,
                                     Map<String, List<String>> owned) {
        List<T> all = new ArrayList<>();
        Map<String, String> owners = new HashMap<>();
        for (Loaded p : loaded) {
            String who = p.who();
            List<T> brought = call(who, p.loader(), method, () -> List.copyOf(contributions.apply(p)), refusal);
            if (brought.size() > Plugin.MAX_CONTRIBUTIONS) {
                throw refusal.apply(who + ": " + method + " brings " + brought.size() + "; at most " + Plugin.MAX_CONTRIBUTIONS, null);
            }
            List<String> keys = new ArrayList<>();
            for (T t : brought) {
                String k = call(who, p.loader(), keyName + " of " + t.getClass().getName(), () -> key.apply(t), refusal);
                if (k == null) throw refusal.apply(who + ": the " + keyName + " of " + t.getClass().getName() + " is null", null);
                if (reserved.containsKey(k)) throw refusal.apply(who + ": the " + keyName + " " + k + " " + reserved.get(k), null);
                String owner = owners.putIfAbsent(k, who);
                if (owner != null) {
                    throw refusal.apply(who + ": the " + keyName + " " + k + " is " + owner + "'s already; a plugin adds and never replaces", null);
                }
                if (check != null && call(who, p.loader(), checked + " of " + t.getClass().getName(), () -> check.apply(t), refusal) == null) {
                    throw refusal.apply(who + ": " + checked + " of " + t.getClass().getName() + " is null", null);
                }
                all.add(t);
                keys.add(k);
            }
            if (!keys.isEmpty()) owned.put(p.name(), keys.stream().sorted().toList());
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

    /** The SHA-256 over {@code jars}; jars that cannot be read are refused, naming the module or plugin found {@code where}. */
    private static String sha256(List<Path> jars, String where) {
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
            throw new IllegalArgumentException(where + ": its jars cannot be read: " + failed, failed);
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
