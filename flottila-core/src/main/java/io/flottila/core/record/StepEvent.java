package io.flottila.core.record;

import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

import java.util.Objects;

/**
 * The JFR event {@value #NAME} (F-21, D-094): emitted only by the flows built with {@code FlowBuilder.stepEvents(true)}, which a
 * node does only when it samples CPU time ({@code FLOTTILA_CPU_SAMPLING}, owner decision 2026-11-03), so a recording on a node
 * without sampling sees none. Such a flow commits one per step a message runs, on the message's thread, from the step's stage
 * being entered until it returns or throws — so it spans the rest of the pipeline (the events of one message nest, the innermost
 * is the step running) and the step's retry, numbering, recording and parking wrappers. It carries the flow, the step's number and
 * its name, never a message id. Disabled unless a recording enables it: the node's CPU sampler does, a {@code default} or
 * {@code profile} recording does not. Core only defines and commits it; matching it to CPU-time samples is the runtime's
 * ({@link StepCpu}).
 *
 * @since 0.1
 */
@Name(StepEvent.NAME)
@Label("Flottila Step")
@Category("Flottila")
@Description("A step of a flow running on the message's thread; the events of one message nest")
@Enabled(false)
@StackTrace(false)
public final class StepEvent extends Event {

    /** The event's name, which a recording enables. */
    public static final String NAME = "io.flottila.Step";

    @Label("Flow")
    private String flow;

    @Label("Step")
    private int step;

    @Label("Name")
    private String name;

    private StepEvent() {}

    /**
     * {@code stage} as the step {@code step} of {@code flow}: an event begins when it is entered and is committed when it returns
     * or throws; its fields are set only when the event is committed (enabled).
     */
    public static Stage marked(Stage stage, String flow, StepConfig step) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(flow, "flow");
        int index = step.index();
        String name = step.name();
        return (e, rest) -> {
            StepEvent event = new StepEvent();
            event.begin();
            try {
                return stage.apply(e, rest);
            } finally {
                if (event.shouldCommit()) {
                    event.flow = flow;
                    event.step = index;
                    event.name = name;
                    event.commit();
                }
            }
        };
    }
}
