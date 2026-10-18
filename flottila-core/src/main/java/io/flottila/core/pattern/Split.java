package io.flottila.core.pattern;

import io.flottila.api.Body;
import io.flottila.api.Envelope;
import io.flottila.api.IdGenerator;
import io.flottila.api.Key;
import io.flottila.api.Pipeline;
import io.flottila.api.Stage;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Splitter (ARCHITECTURE §4): the parent's body is cut into parts by {@code parts}, every part becomes a child
 * message with its own id from the injected generator, the parent's headers, keys and attachments, and the props
 * {@link #PARENT_ID}, {@link #SPLIT_INDEX} and {@link #SPLIT_TOTAL}; each child runs the rest of the pipeline in
 * order, on the parent's thread, and the parent is handed back. A child is the next step of its parent. The
 * splitter stores the parts (bytes exist once; the parts are keyed by the parent's id and the child's step) and
 * the children reference them.
 *
 * @param parts cuts the parent into the children's bodies; an empty list means no children and no error
 * @param ids the children's ids
 * @since 0.1
 */
public record Split(Function<Envelope, List<Body>> parts, IdGenerator ids) implements Stage {

    /** The parent's message id, on every child. */
    public static final Key<String> PARENT_ID = new Key<>("splitParentId");
    /** The child's position, from 0. */
    public static final Key<Integer> SPLIT_INDEX = new Key<>("splitIndex");
    /** How many children the parent was split into. */
    public static final Key<Integer> SPLIT_TOTAL = new Key<>("splitTotal");

    public Split {
        Objects.requireNonNull(parts, "parts");
        Objects.requireNonNull(ids, "ids");
    }

    @Override
    public Envelope apply(Envelope parent, Pipeline rest) {
        List<Body> bodies = parts.apply(parent);
        int total = bodies.size();
        for (int i = 0; i < total; i++) {
            Envelope child = parent.next(bodies.get(i)).withId(ids.next())
                    .with(PARENT_ID, parent.id()).with(SPLIT_INDEX, i).with(SPLIT_TOTAL, total);
            rest.apply(child);
        }
        return parent;
    }
}
