package dev.rail8.api.plugin;

import dev.rail8.api.Component;
import dev.rail8.api.Expressions;
import dev.rail8.api.StepFactory;

import java.util.List;

/**
 * A plugin: what adds components, step factories and expression languages to a node. The node's own parts are plugins too, and
 * register the same way: each shipped component and step (http, sftp, direct, xslt with xpath, xsd, jq, mapping) is a module of
 * the node's build, found on the node's class path. A plugin from elsewhere is one jar, or one directory of jars, in the node's
 * plugin directory ({@code RAIL8_PLUGINS}). The node finds both with {@link java.util.ServiceLoader}: a jar names its
 * implementation in {@code META-INF/services/dev.rail8.api.plugin.Plugin}, a public class with a public constructor without
 * arguments that does nothing else; a plugin of the plugin directory names exactly one.
 *
 * <p>What the node calls, one call at a time, each with the calling thread's context class loader set to the plugin's own for
 * that call only (a consumer, producer or step runs later on the node's threads, with the node's):
 * <ul>
 * <li>{@link #name()} and {@link #api()} once, when it loads the plugin;</li>
 * <li>{@link #steps(PluginSettings)} and {@link #languages()} once each, when it loads the plugin, before Vert.x starts;</li>
 * <li>{@link #components(ComponentContext)} once per node start, inside the start and before Postgres is touched, and once per
 *     {@code rail8 validate} or {@code rail8 test} run; {@code rail8 test} builds the components of the platform's
 *     {@code direct} module again for each case of a package with a {@code direct} endpoint. It builds objects and opens
 *     nothing: no socket, no file, no thread.</li>
 * </ul>
 * A call that throws refuses the start, naming the plugin; so do more than {@value #MAX_CONTRIBUTIONS} contributions of one kind.
 *
 * <p>A plugin adds and never replaces: a scheme, step type or expression language the node already has (a module's, built in or
 * another plugin's) refuses the start, naming both owners, and so does the scheme {@code mock}, which is the platform's. The
 * modules come first, in name order, then the plugins of the plugin directory in entry order, so a module always owns what it
 * brings. A plugin brings no mock of its own: its mocks stay in its tests, and {@code rail8 test} stands the platform's mock in
 * for its schemes.
 *
 * <p>What a plugin of the plugin directory sees: the JDK, its own jars and, from the node, {@code dev.rail8.api},
 * {@code io.vertx.core}, {@code io.netty} and {@code org.slf4j}. Nothing else of the node is visible to it (core, runtime, the
 * modules, their libraries). Each such plugin has a class loader of its own, so two plugins may bring two versions of one
 * library. A module shares the node's class loader.
 *
 * <p>A component's acknowledgement and back-pressure are its handler's {@code Future<Ack>}: {@code Accepted} once the message is
 * durable, {@code Reply} for a sync flow, {@code Nack} for a refusal, retryable or for good. A flow with no room answers a retryable
 * {@code Nack} before anything runs. A sealed type a plugin switches on, such as {@code Ack}, never hands it a variant it did not
 * opt into.
 *
 * <p>Compatibility: {@link #api()} returns {@link #API} as compiled into the plugin. While the api is 0.x, a node loads a plugin
 * of its own major.minor only; from 1.0 on, one of its own major and a minor up to its own. A later kind of contribution comes as
 * a new default method, so a plugin built against an older minor still compiles and loads.
 *
 * @since 0.1
 */
public interface Plugin {

    /**
     * The api version of this interface, {@code major.minor}. It is a constant, so the {@code return API;} of {@link #api()}
     * compiles the version the plugin was built against into the plugin.
     */
    String API = "0.3";

    /** The most contributions of one kind (components, step factories, expression languages) a plugin may bring. */
    int MAX_CONTRIBUTIONS = 256;

    /**
     * The plugin's name, unique on a node: a lower-case letter, then at most 63 lower-case letters, digits or dashes. The name
     * {@code rail8} stands for the platform itself (its built-in steps and its mock) and is no plugin's.
     */
    String name();

    /** The api version the plugin was built against: implement it as {@code return API;}. */
    String api();

    /** The components the plugin adds, built over the node's {@code context}; none by default. */
    default List<Component> components(ComponentContext context) {
        return List.of();
    }

    /** The step factories the plugin adds; none by default. The node calls {@link #steps(PluginSettings)}, which returns these. */
    default List<StepFactory> steps() {
        return List.of();
    }

    /**
     * The step factories the plugin adds, built over its {@code settings}; those of {@link #steps()} by default. This is the
     * method the node calls.
     *
     * @param settings the plugin's settings ({@code RAIL8_<NAME>_*}); a node reads them from its environment, {@code rail8
     *                 validate} and {@code rail8 test} pass none
     * @since 0.2
     */
    default List<StepFactory> steps(PluginSettings settings) {
        return steps();
    }

    /** The expression languages the plugin adds; none by default. */
    default List<Expressions> languages() {
        return List.of();
    }
}
