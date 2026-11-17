package dev.rail8.jq.oracle.jqtest;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.rail8.jq.Bindings;
import dev.rail8.jq.JqCompileException;
import dev.rail8.jq.JqEngine;
import dev.rail8.jq.JqException;
import dev.rail8.jq.JqFilter;
import dev.rail8.jq.JqFilterException;
import dev.rail8.jq.JqSyntaxException;
import dev.rail8.jq.JqTermination;
import dev.rail8.jq.JsonDoc;
import dev.rail8.jq.JsonWriter;
import dev.rail8.jq.Limits;
import dev.rail8.jq.OutputOptions;
import dev.rail8.jq.Workspace;
import dev.rail8.jq.host.Host;
import dev.rail8.jq.value.JqArray;
import dev.rail8.jq.value.JqBool;
import dev.rail8.jq.value.JqNull;
import dev.rail8.jq.value.JqNumber;
import dev.rail8.jq.value.JqObject;
import dev.rail8.jq.value.JqString;
import dev.rail8.jq.value.JqValue;
import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The jqtest oracle's engine: the public API with {@link Limits#embedded()} without the deadline and
 * {@link Host#sandboxed()} (ADR-0020, ADR-0024). It lives with the tests because the oracle module does not read
 * jq-core.
 *
 * <p>The API does not tell a literal number from a computed one, so every number is taken as a literal: a computed
 * result is compared by {@link JqNumber#decimalValue()}, its printed digits, where jq compares doubles. That differs
 * only for an expected literal with more digits than its double prints.
 */
final class ApiEngine implements Engine {

  // ADR-0020:50: the host is to carry UTC, a ModuleLoader over jq/tests/modules, debug discarded and, for man.test,
  // environment({PAGER=less}); the API has no environment or ModuleLoader yet, so it is the sandbox for every file.
  private final JqEngine engine = JqEngine.builder().limits(limits()).host(Host.sandboxed()).build();

  /** {@link Limits#embedded()} with an unlimited deadline and jq's recursion depth from {@link Limits#cli()}. */
  static Limits limits() {
    Limits e = Limits.embedded();
    return new Limits(
        e.steps(), Limits.UNLIMITED_TIME, e.nestingDepth(), Limits.cli().recursionDepth(), e.bytesBuilt(), e.bytesWritten(), e.regexMatchTime(), e.stackBytes());
  }

  @Override
  public Filter compile(String program) {
    JqFilter filter = engine(() -> engine.compile(program));
    return (input, sink) ->
        engine(
            () -> {
              run(filter, input, sink);
              return null;
            });
  }

  private void run(JqFilter filter, String input, Predicate<JvValue> sink) {
    Workspace workspace = engine.newWorkspace();
    try (JsonDoc doc = workspace.read(utf8(input))) {
      filter.run(doc.root(), Bindings.builder().build(), v -> sink.test(copy(v)), workspace);
    }
  }

  @Override
  public JvValue read(String json) {
    return engine(
        () -> {
          try (JsonDoc doc = engine.newWorkspace().read(utf8(json))) {
            return copy(doc.root());
          }
        });
  }

  @Override
  public JvValue reread(String json) {
    return engine(
        () -> {
          Workspace workspace = engine.newWorkspace();
          ByteArrayOutputStream written = new ByteArrayOutputStream();
          try (JsonDoc doc = workspace.read(utf8(json))) {
            JsonWriter.sink(workspace, written, OutputOptions.compact()).emit(doc.root());
          }
          try (JsonDoc again = workspace.read(MemorySegment.ofArray(written.toByteArray()))) {
            return copy(again.root());
          }
        });
  }

  private static MemorySegment utf8(String text) {
    return MemorySegment.ofArray(text.getBytes(UTF_8));
  }

  /** Calls the engine; one of its exceptions becomes an {@link EngineException}, any other passes. */
  private static <T> T engine(Supplier<T> call) {
    try {
      return call.get();
    } catch (JqException e) {
      throw translate(e);
    }
  }

  /** The runner's view of the engine's four exceptions; it reads no value, so it cannot meet a Stub itself. */
  static EngineException translate(JqException e) {
    return switch (e) {
      case JqCompileException c -> new EngineException(EngineException.Kind.COMPILE, c.getMessage(), c.blocks());
      case JqSyntaxException s -> new EngineException(EngineException.Kind.SYNTAX, s.getMessage(), List.of());
      case JqFilterException _ -> new EngineException(EngineException.Kind.FILTER, "error", List.of());
      case JqTermination t -> new EngineException(EngineException.Kind.TERMINATION, t.cause() + " exit " + t.exitCode(), List.of());
    };
  }

  /** The oracle's copy of a value, made during its sink call while it is valid (ADR-0016). */
  static JvValue copy(JqValue v) {
    return switch (v) {
      case JqNull _ -> JvValue.Constant.NULL;
      case JqBool b -> b.value() ? JvValue.Constant.TRUE : JvValue.Constant.FALSE;
      case JqNumber x -> new JvValue.Num(x.doubleValue(), decimal(x), true);
      case JqString s -> new JvValue.Str(s.utf8().toArray(ValueLayout.JAVA_BYTE));
      case JqArray a -> {
        List<JvValue> elements = new ArrayList<>(a.length());
        for (int i = 0; i < a.length(); i++) elements.add(copy(a.get(i)));
        yield new JvValue.Arr(elements);
      }
      case JqObject o -> {
        Map<String, JvValue> members = new LinkedHashMap<>();
        o.forEach((k, m) -> members.put(k, copy(m)));
        yield new JvValue.Obj(members);
      }
    };
  }

  /** The exact value, null for NaN and the infinities, which have none. */
  private static BigDecimal decimal(JqNumber x) {
    try {
      return x.decimalValue();
    } catch (ArithmeticException e) {
      return null;
    }
  }
}
