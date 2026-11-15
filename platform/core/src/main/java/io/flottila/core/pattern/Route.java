package io.flottila.core.pattern;

import io.flottila.api.Envelope;
import io.flottila.api.Key;
import io.flottila.api.Pipeline;
import io.flottila.api.Stage;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Content-based router (ARCHITECTURE §4): the first branch whose predicate matches sets {@code prop} to its
 * value, else {@code otherwise} does; with no match and no {@code otherwise} the message fails — never dropped
 * silently. The decision travels as a prop; a later {@code to … byProp} step acts on it.
 *
 * @param prop the prop that carries the decision
 * @param branches tried in order; at least one
 * @param otherwise the value when no branch matches
 * @since 0.1
 */
public record Route(Key<String> prop, List<Branch> branches, Optional<String> otherwise) implements Stage {

    /**
     * One branch.
     *
     * @param value what the prop is set to when {@code when} matches
     * @param when the condition
     * @since 0.1
     */
    public record Branch(String value, Predicate<Envelope> when) {
        public Branch {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(when, "when");
        }
    }

    public Route {
        Objects.requireNonNull(prop, "prop");
        Objects.requireNonNull(otherwise, "otherwise");
        branches = List.copyOf(branches);
        if (branches.isEmpty()) throw new IllegalArgumentException("route on " + prop.name() + " needs at least one branch");
    }

    @Override
    public Envelope apply(Envelope e, Pipeline rest) {
        for (Branch b : branches) {
            if (b.when().test(e)) return rest.apply(e.with(prop, b.value()));
        }
        String fallback = otherwise.orElseThrow(() -> new IllegalStateException("no route matched for " + prop.name() + " and the route has no 'otherwise'"));
        return rest.apply(e.with(prop, fallback));
    }
}
