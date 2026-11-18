package dev.rail8.runtime.plugin.fixture;

import dev.rail8.api.Ack;
import dev.rail8.api.Component;
import dev.rail8.api.Consumer;
import dev.rail8.api.EndpointConfig;
import dev.rail8.api.Envelope;
import dev.rail8.api.Expressions;
import dev.rail8.api.Health;
import dev.rail8.api.Producer;
import dev.rail8.api.Stage;
import dev.rail8.api.StepConfig;
import dev.rail8.api.StepFactory;
import dev.rail8.api.StepResources;
import dev.rail8.api.plugin.ComponentContext;
import dev.rail8.api.plugin.Plugin;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Plugins, and what they bring, for the loader's tests. A test writes the one it needs into a jar of its own, with every class
 * of this file ({@link #classes()}), so the loader defines copies of them in the plugin's class loader; the test's own copies
 * are never the plugin's.
 */
public final class PluginFixtures {

    private PluginFixtures() {}

    /** This class and every class nested in it: what each fixture jar holds. */
    public static Class<?>[] classes() {
        return Stream.concat(Stream.of(PluginFixtures.class), Stream.of(PluginFixtures.class.getDeclaredClasses())).toArray(Class<?>[]::new);
    }

    /** A component of {@code scheme} that is never started; built over a node's context, it keeps that context's Vert.x. */
    public static final class Named implements Component {
        private final String scheme;
        private final Vertx vertx;

        public Named(String scheme, ComponentContext context) {
            this.scheme = scheme;
            this.vertx = context.vertx();
        }

        /** The Vert.x of the context it was built over. */
        public Vertx vertx() {
            return vertx;
        }

        @Override public String scheme() { return scheme; }
        @Override public String configSchema() { return "{\"type\":\"object\"}"; }
        @Override public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) { throw new UnsupportedOperationException("never started"); }
        @Override public Producer producer(EndpointConfig cfg) { throw new UnsupportedOperationException("never started"); }
        @Override public Future<Health> health() { return Future.succeededFuture(Health.up()); }
    }

    /** A step factory of {@code type} whose stage hands the envelope on. */
    public record Step(String type) implements StepFactory {
        @Override public String configSchema() { return "{\"type\":\"object\"}"; }
        @Override public Stage create(StepConfig step, StepResources resources) { return (e, rest) -> rest.apply(e); }
    }

    /** An expression language named {@code language} whose conditions are false and whose values are empty. */
    public record Language(String language) implements Expressions {
        @Override public Predicate<Envelope> predicate(String expression, StepResources resources) { return e -> false; }
        @Override public Function<Envelope, Optional<String>> value(String expression, StepResources resources) { return e -> Optional.empty(); }
    }

    /** A plugin named {@code acme}: the component {@code acme}, the step {@code sign} and the language {@code upper}. */
    public static final class Acme implements Plugin {
        @Override public String name() { return "acme"; }
        @Override public String api() { return API; }
        @Override public List<Component> components(ComponentContext context) { return List.of(new Named("acme", context)); }
        @Override public List<StepFactory> steps() { return List.of(new Step("sign")); }
        @Override public List<Expressions> languages() { return List.of(new Language("upper")); }
    }

    /** A plugin named {@code beta} whose component's scheme is {@code acme}, as the plugin {@code acme}'s is. */
    public static final class BetaWithAcmesScheme implements Plugin {
        @Override public String name() { return "beta"; }
        @Override public String api() { return API; }
        @Override public List<Component> components(ComponentContext context) { return List.of(new Named("acme", context)); }
    }

    /** A plugin named {@code acme} that brings nothing: a second plugin of {@link Acme}'s name. */
    public static final class AcmeAgain implements Plugin {
        @Override public String name() { return "acme"; }
        @Override public String api() { return API; }
    }

    /** A plugin that brings a step of the shipped type {@code xslt}. */
    public static final class ShippedStepType implements Plugin {
        @Override public String name() { return "shipped-step"; }
        @Override public String api() { return API; }
        @Override public List<StepFactory> steps() { return List.of(new Step("xslt")); }
    }

    /** A plugin that brings a step of the built-in type {@code route}. */
    public static final class BuiltInStepType implements Plugin {
        @Override public String name() { return "built-in-step"; }
        @Override public String api() { return API; }
        @Override public List<StepFactory> steps() { return List.of(new Step("route")); }
    }

    /** A plugin that brings the shipped language {@code xpath}. */
    public static final class ShippedLanguage implements Plugin {
        @Override public String name() { return "shipped-language"; }
        @Override public String api() { return API; }
        @Override public List<Expressions> languages() { return List.of(new Language("xpath")); }
    }

    /** A plugin that brings a component of the shipped scheme {@code http}. */
    public static final class ShippedScheme implements Plugin {
        @Override public String name() { return "shipped-scheme"; }
        @Override public String api() { return API; }
        @Override public List<Component> components(ComponentContext context) { return List.of(new Named("http", context)); }
    }

    /** A plugin that brings a component of the platform's scheme {@code mock}. */
    public static final class MockScheme implements Plugin {
        @Override public String name() { return "mock-scheme"; }
        @Override public String api() { return API; }
        @Override public List<Component> components(ComponentContext context) { return List.of(new Named("mock", context)); }
    }

    /** A plugin that brings one step factory more than a plugin may. */
    public static final class TooManySteps implements Plugin {
        @Override public String name() { return "too-many"; }
        @Override public String api() { return API; }
        @Override public List<StepFactory> steps() {
            return IntStream.rangeClosed(0, MAX_CONTRIBUTIONS).<StepFactory>mapToObj(i -> new Step("s" + i)).toList();
        }
    }

    /** A plugin whose {@code steps()} throws. */
    public static final class FailingSteps implements Plugin {
        @Override public String name() { return "failing"; }
        @Override public String api() { return API; }
        @Override public List<StepFactory> steps() { throw new IllegalStateException("no steps today"); }
    }

    /** A plugin whose {@code components()} reaches a class of the node that a plugin does not see. */
    public static final class ReachesIntoTheNode implements Plugin {
        @Override public String name() { return "reaches"; }
        @Override public String api() { return API; }
        @Override public List<Component> components(ComponentContext context) {
            return List.of(new Named(dev.rail8.runtime.Main.class.getSimpleName(), context));
        }
    }

    /**
     * A plugin that checks the context class loader of each call the node makes into it (its constructor, {@code name()},
     * {@code api()}, {@code steps()}) and answers in its one step's type: {@code own-loader} when every call saw the plugin's own
     * class loader, else {@code other-loader}.
     */
    public static final class ChecksTheContextLoader implements Plugin {
        private final List<ClassLoader> seen = new ArrayList<>();

        public ChecksTheContextLoader() {
            seen.add(Thread.currentThread().getContextClassLoader());
        }

        @Override public String name() {
            seen.add(Thread.currentThread().getContextClassLoader());
            return "checks";
        }

        @Override public String api() {
            seen.add(Thread.currentThread().getContextClassLoader());
            return API;
        }

        @Override public List<StepFactory> steps() {
            seen.add(Thread.currentThread().getContextClassLoader());
            boolean own = seen.size() == 4 && seen.stream().allMatch(l -> l == ChecksTheContextLoader.class.getClassLoader());
            return List.of(new Step(own ? "own-loader" : "other-loader"));
        }
    }

    /** A plugin named as the node's own set is. */
    public static final class NamedRail8 implements Plugin {
        @Override public String name() { return "rail8"; }
        @Override public String api() { return API; }
    }

    /** A plugin whose name has an upper-case letter. */
    public static final class BadName implements Plugin {
        @Override public String name() { return "Acme"; }
        @Override public String api() { return API; }
    }

    /** A plugin built against the next api minor. */
    public static final class NextMinor implements Plugin {
        @Override public String name() { return "next-minor"; }
        @Override public String api() { return "0.2"; }
    }

    /** A plugin whose api is no version. */
    public static final class NoVersion implements Plugin {
        @Override public String name() { return "no-version"; }
        @Override public String api() { return "latest"; }
    }
}
