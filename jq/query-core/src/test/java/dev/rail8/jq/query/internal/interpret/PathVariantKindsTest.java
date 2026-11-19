package dev.rail8.jq.query.internal.interpret;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rail8.jq.query.run.Path;
import dev.rail8.jq.query.run.PathSink;
import dev.rail8.jq.query.run.RunState;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Every kind of op is classified for the path variants: a kind that passes paths on overrides both {@code runPath} and
 * {@code startPath}, and an untracked kind keeps both defaults, which emit each of its results untracked. A new kind
 * fails here until it is put in one of the two sets, and so does a kind that overrides one of the two methods without
 * the other.
 */
class PathVariantKindsTest {
  /** The kinds that pass paths on, or extend them. */
  private static final Set<Class<?>> PASSING =
      Set.of(
          IdentityOp.class,
          VarOp.class,
          OuterVarOp.class,
          PipeOp.class,
          CommaOp.class,
          BindOp.class,
          BindsOp.class,
          IfOp.class,
          AltOp.class,
          AltBindOp.class,
          TryOp.class,
          LabelOp.class,
          ReduceOp.class,
          ForeachOp.class,
          AdvanceOp.class,
          IntrinsicOp.class,
          Intrinsic1Op.class,
          Intrinsic2Op.class,
          IntrinsicNOp.class,
          IntrinsicManyOp.class,
          CallOp.class,
          JumpOp.class,
          ParamCallOp.class);

  /** The kinds whose results are untracked. */
  private static final Set<Class<?>> UNTRACKED = Set.of(ConstOp.class, CollectOp.class, BreakOp.class, PathOfOp.class, UpdateOp.class);

  @Test
  void everyKindIsClassifiedAndOverridesBothPathMethodsOrNeither() {
    for (Class<?> kind : Op.class.getPermittedSubclasses()) {
      boolean run = declares(kind, "runPath", RunState.class, Object[].class, Object.class, Path.class, PathSink.class);
      boolean start = declares(kind, "startPath", Trampoline.class, HeapForm.class, Object[].class, Object.class, Path.class, HeapPathSink.class);
      assertThat(start).as("whether %s overrides startPath, against runPath", kind.getSimpleName()).isEqualTo(run);
      assertThat(PASSING.contains(kind) || UNTRACKED.contains(kind)).as("whether %s is classified", kind.getSimpleName()).isTrue();
      assertThat(run).as("whether %s overrides the path variants", kind.getSimpleName()).isEqualTo(PASSING.contains(kind));
    }
  }

  /** Whether {@code kind} declares the method {@code name} of {@code parameters} itself. */
  private static boolean declares(Class<?> kind, String name, Class<?>... parameters) {
    try {
      return kind.getDeclaredMethod(name, parameters).getDeclaringClass() == kind;
    } catch (NoSuchMethodException _) {
      return false;
    }
  }
}
