package dev.rail8.jq.query.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rail8.jq.query.ir.Call;
import dev.rail8.jq.query.ir.Function;
import dev.rail8.jq.query.ir.Identity;
import dev.rail8.jq.query.ir.Node;
import dev.rail8.jq.query.ir.PathOf;
import dev.rail8.jq.query.ir.Program;
import dev.rail8.jq.query.ir.Source;
import dev.rail8.jq.query.ir.Update;
import dev.rail8.jq.query.run.InertPaths;
import dev.rail8.jq.query.run.PathFormat;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * A PathOf's body and both sides of an Update are no tail positions: the PathOf makes a value of each result's path,
 * and the Update edits its input after each lhs result and emits after the last one, so a recursive Call there is
 * RECURSIVE, never a tail call.
 */
class PathCallKindsTest {
  private static final Source AT = new Source(0, 1);

  private static final PathFormat PATHS = new InertPaths();

  /** The kind of the one Call of function 0, whose body is {@code body}; main calls it. */
  private static CallKind kind(Node body) {
    Program program = new Program(new Call(AT, 0, List.of(), List.of()), List.of(new Function(AT, 0, 0, body)));
    Numbering numbering = Numbering.of(program);
    CallGraph graph = CallGraph.of(program, numbering);
    CallKinds kinds = CallKinds.optimised(numbering, graph, Facts.optimised(numbering, graph));
    List<Node> nodes = numbering.nodes(0);
    CallKind found = null;
    for (int id = 0; id < nodes.size(); id++) {
      if (nodes.get(id) instanceof Call) {
        assertThat(found).as("premise: one Call in the body").isNull();
        found = kinds.kind(0, id);
      }
    }
    return found;
  }

  private static Call recurse() {
    return new Call(AT, 0, List.of(), List.of());
  }

  @Test
  void aCallAtTheRootIsATailCall() {
    assertThat(kind(recurse())).isEqualTo(CallKind.TAIL_SELF);
  }

  @Test
  void aCallInAPathOfOrAnUpdateIsRecursive() {
    List<Supplier<Node>> bodies =
        List.of(
            () -> new PathOf(AT, recurse(), PATHS),
            () -> new Update(AT, Update.Kind.MODIFY, recurse(), new Identity(AT), PATHS),
            () -> new Update(AT, Update.Kind.MODIFY, new Identity(AT), recurse(), PATHS),
            () -> new Update(AT, Update.Kind.ASSIGN, recurse(), new Identity(AT), PATHS));
    for (Supplier<Node> body : bodies) {
      assertThat(kind(body.get())).as("the Call in %s", body.get()).isEqualTo(CallKind.RECURSIVE);
    }
  }
}
