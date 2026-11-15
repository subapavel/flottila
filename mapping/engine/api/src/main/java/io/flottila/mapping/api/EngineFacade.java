package io.flottila.mapping.api;

import io.flottila.mapping.ir.IrOptimiser;
import io.flottila.mapping.ir.TargetDocument;
import io.flottila.mapping.mapping.AtPointer;
import io.flottila.mapping.mapping.Diagnostic;
import io.flottila.mapping.mapping.FunctionCatalogue;
import io.flottila.mapping.mapping.LoadResult;
import io.flottila.mapping.mapping.Loaded;
import io.flottila.mapping.mapping.Lowered;
import io.flottila.mapping.mapping.Lowering;
import io.flottila.mapping.mapping.MalformedJsonException;
import io.flottila.mapping.mapping.MappingDiagnostics;
import io.flottila.mapping.mapping.MappingModel;
import io.flottila.mapping.mapping.MappingModelFormat;
import io.flottila.mapping.mapping.Refused;
import io.flottila.mapping.mapping.Severity;
import io.flottila.mapping.mapping.StructureRef;
import io.flottila.mapping.runtime.Interpreter;
import io.flottila.mapping.runtime.MalformedInputException;
import io.flottila.mapping.runtime.MappingRunException;
import io.flottila.mapping.runtime.Tracer;
import io.flottila.mapping.structure.Structure;
import io.flottila.mapping.structure.importer.ReferenceResolver;
import io.flottila.mapping.xslt.export.XsltExporter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * The engine facade the service calls (ADR-0016). It carries no compatibility promise and is not
 * the embedding API for Flottila.
 */
public final class EngineFacade {

  /** The runtime error of a test run that exceeds its timeout (ADR-0014, ADR-0015). */
  private static final String TIMEOUT = "flottila:TIMEOUT";

  /** How a test run waits by the wall clock. */
  private static final Deadline WALL_CLOCK =
      (run, timeout, tracer) -> run.get(timeout.toNanos(), TimeUnit.NANOSECONDS);

  /** How {@link #testRun} waits for its run: the wall clock, unless a test gives its own. */
  private final Deadline deadline;

  /** Where each run takes its time from; its zone gives the run's time zone (#80). */
  private final Clock clock;

  /**
   * A facade; it holds no mutable state, its runs take their time from the UTC system clock, and
   * its test runs wait by the wall clock.
   */
  public EngineFacade() {
    this(Clock.systemUTC());
  }

  /**
   * A facade whose runs take their time from {@code clock} (#80): each run reads it once as it
   * starts, and the offset of that time in the clock's zone is the run's time zone, in which a date
   * without an offset is read and every date is written. Its test runs wait by the wall clock.
   */
  public EngineFacade(Clock clock) {
    this(WALL_CLOCK, clock);
  }

  /** A facade whose test runs wait by {@code deadline}: a test's fake clock (CODING_STANDARDS). */
  EngineFacade(Deadline deadline) {
    this(deadline, Clock.systemUTC());
  }

  private EngineFacade(Deadline deadline, Clock clock) {
    this.deadline = deadline;
    this.clock = clock;
  }

  /**
   * How a test run waits for its run until its timeout (ADR-0014): the wall clock, or a test's
   * clock that reaches the timeout at a point of the run it picks by the run's tracer.
   */
  @FunctionalInterface
  interface Deadline {

    /**
     * The run's output once it is done.
     *
     * @throws TimeoutException once the timeout is reached first
     * @throws ExecutionException when the run failed, its cause
     * @throws InterruptedException when the waiting thread is interrupted
     */
    byte[] await(Future<byte[]> run, Duration timeout, Tracer tracer)
        throws InterruptedException, ExecutionException, TimeoutException;
  }

  /**
   * Loads a mapping model through validation stages 1 and 2 (ADR-0011).
   *
   * @param json the document's bytes
   * @return the model, or the diagnostics that refuse it
   * @throws MalformedJsonException if the bytes are no JSON document
   */
  public LoadResult load(byte[] json) throws MalformedJsonException {
    return MappingModelFormat.load(json);
  }

  /** The canonical form of {@code model} (ADR-0010). */
  public byte[] save(MappingModel model) {
    return MappingModelFormat.save(model);
  }

  /**
   * The published function catalogue (ADR-0026): the bytes of the packed {@code
   * function-catalogue.json}, an instance of {@code contract/function-catalogue.schema.json}. Each
   * call gives a copy of its own.
   */
  public byte[] catalogue() {
    return FunctionCatalogue.bytes();
  }

  /**
   * A new mapping model in {@code formatVersion} 1 (ADR-0010) between two structures, with no
   * description, no target field mapping and no UDF.
   *
   * @param source the source structure's schema bundle, root and fingerprint
   * @param target the target structure's schema bundle, root and fingerprint
   * @return the model, not yet saved
   */
  public MappingModel newMapping(StructureRef source, StructureRef target) {
    return newMapping(Optional.empty(), Optional.of(source), Optional.of(target));
  }

  /**
   * A new mapping model in {@code formatVersion} 1 (ADR-0010) with no target field mapping and no
   * UDF, and the structures chosen so far: a new mapping may have neither until they are chosen
   * (ADR-0029).
   *
   * @param description the mapping's description, if any
   * @param source the source structure's schema bundle, root and fingerprint, if chosen
   * @param target the target structure's schema bundle, root and fingerprint, if chosen
   * @return the model, not yet saved
   */
  public MappingModel newMapping(
      Optional<String> description, Optional<StructureRef> source, Optional<StructureRef> target) {
    return new MappingModel(1, description, source, target, Map.of(), Map.of());
  }

  /**
   * Compiles a mapping model (ADR-0016): load with stages 1 and 2, the source and target
   * structures, stage 3 and lowering, the optimiser if {@code options} ask for it, then the closure
   * compile. The first of these that refuses the model gives the result; the structures are both
   * read before either refusal is reported. A model without its source or target is refused with
   * {@code missing-structure} before any bundle is read (ADR-0029). Stage 3 checks each side's
   * stored fingerprint against the structure just read (ADR-0009 as amended on 2026-11-05, #91),
   * and a mismatch is reported with lowering's diagnostics. The compiled mapping keeps the
   * occurrence of each target field mapping's field, which a generating function's trace entry
   * shows (#83). An unchecked exception from {@code resolver} passes through unchanged.
   *
   * @param model the mapping model's bytes
   * @param resolver the host's resolver of the model's schema bundle references (ADR-0014
   *     "Reference resolver shape")
   * @return the compiled mapping, or the diagnostics that refuse it
   * @throws MalformedJsonException if the bytes are no JSON document
   */
  public CompileResult compile(byte[] model, ReferenceResolver resolver, CompileOptions options)
      throws MalformedJsonException {
    return loadAndLower(
        model,
        resolver,
        CompileResult.Refused::new,
        lowered -> {
          TargetDocument document = lowered.document();
          // lowering has verified the IR; the optimiser verifies it again (ADR-0006)
          TargetDocument ir = options.optimised() ? IrOptimiser.optimise(document) : document;
          return new CompileResult.Compiled(
              new CompiledMapping(
                  Interpreter.compile(ir, options.traced()),
                  Lowering.occurrences(lowered.model(), lowered.target())));
        });
  }

  /**
   * The diagnostics of a compile of a mapping model (ADR-0015, ADR-0027), without the closure
   * compile: those of the first of load, structures, and stage 3 with lowering that refuses the
   * model, as {@link #compile} reports them, and none when it compiles. A model that passes stages
   * 1 and 2 adds the {@code unreachable-box} warnings (ADR-0011, ADR-0013), whichever later stage
   * refuses it. The result is sorted by {@link Diagnostic#ORDER}. An unchecked exception from
   * {@code resolver} passes through unchanged.
   *
   * @param model the mapping model's bytes
   * @param resolver the host's resolver of the model's schema bundle references (ADR-0014
   *     "Reference resolver shape")
   * @return every diagnostic, the warnings among them
   * @throws MalformedJsonException if the bytes are no JSON document
   */
  public List<Diagnostic> diagnostics(byte[] model, ReferenceResolver resolver)
      throws MalformedJsonException {
    return switch (MappingModelFormat.load(model)) {
      case Refused r -> r.diagnostics();
      case Loaded(MappingModel loaded) -> {
        List<Diagnostic> found =
            new ArrayList<>(lower(loaded, resolver, refusals -> refusals, lowered -> List.of()));
        found.addAll(Lowering.unreachable(loaded));
        yield DiagnosticOrder.sorted(found);
      }
    };
  }

  /**
   * Exports a mapping model to XSLT 3.0 in the portable profile (ADR-0023): load, structures, stage
   * 3 and lowering as {@link #compile} does them, then the export of the unoptimised IR. A refused
   * model gives no stylesheet, not even of the target fields that would export. An unchecked
   * exception from {@code resolver} passes through unchanged.
   *
   * @param model the mapping model's bytes
   * @param resolver the host's resolver of the model's schema bundle references (ADR-0014
   *     "Reference resolver shape")
   * @return the stylesheet and its helper module, or the diagnostics that refuse the model
   * @throws MalformedJsonException if the bytes are no JSON document
   */
  public ExportResult export(byte[] model, ReferenceResolver resolver)
      throws MalformedJsonException {
    return loadAndLower(
        model,
        resolver,
        ExportResult.Refused::new,
        lowered ->
            new ExportResult.Exported(
                XsltExporter.export(lowered.document()), XsltExporter.helperModule()));
  }

  /**
   * Refreshes a mapping model (ADR-0009 as amended on 2026-11-05, ADR-0015): load with stages 1 and
   * 2, then the source and target structures the model has; a side not chosen yet is not read and
   * stays absent (ADR-0029). Each side whose bundle loads gets the fingerprint of the structure
   * just read; a side whose bundle fails to load keeps its stored fingerprint and gives every load
   * error (#93). When the model has both sides and both load, the refreshed model goes through
   * stage 3 and lowering, whose diagnostics, the orphaned references among them, are the result's;
   * it has no {@code fingerprint-mismatch}, as both fingerprints were just derived, and no
   * occurrence or type warning. Otherwise stage 3 is not run, and the load errors, if any, are the
   * result's only diagnostics. Nothing else in the model changes, its description and layouts
   * included, and it comes back in canonical form (ADR-0010); nothing is written. An unchecked
   * exception from {@code resolver} passes through unchanged.
   *
   * @param model the mapping model's bytes
   * @param resolver the host's resolver of the model's schema bundle references (ADR-0014
   *     "Reference resolver shape")
   * @return the refreshed model and its diagnostics, or the diagnostics of stage 1 or 2 that refuse
   *     the model
   * @throws MalformedJsonException if the bytes are no JSON document
   */
  public RefreshResult refresh(byte[] model, ReferenceResolver resolver)
      throws MalformedJsonException {
    return switch (MappingModelFormat.load(model)) {
      case Refused r -> new RefreshResult.Refused(r.diagnostics());
      case Loaded(MappingModel loaded) -> {
        List<Diagnostic> diagnostics = new ArrayList<>();
        Optional<Structure> source =
            loaded.source().flatMap(ref -> structure(resolver, ref, diagnostics));
        Optional<Structure> target =
            loaded.target().flatMap(ref -> structure(resolver, ref, diagnostics));
        MappingModel refreshed =
            new MappingModel(
                loaded.formatVersion(),
                loaded.description(),
                loaded.source().map(ref -> refreshed(ref, source)),
                loaded.target().map(ref -> refreshed(ref, target)),
                loaded.targetFieldMappings(),
                loaded.udfs());
        // both structures are there exactly when the model has both sides and both loaded
        if (source.isPresent()
            && target.isPresent()
            && Lowering.lower(refreshed, source.get(), target.get())
                instanceof Refused(List<Diagnostic> stage3)) {
          diagnostics.addAll(stage3);
        }
        yield new RefreshResult.Refreshed(MappingModelFormat.save(refreshed), diagnostics);
      }
    };
  }

  /** {@code ref} with the fingerprint of {@code structure}, or as it is when there is none. */
  private static StructureRef refreshed(StructureRef ref, Optional<Structure> structure) {
    return structure
        .map(s -> new StructureRef(ref.bundle(), ref.root(), s.fingerprint()))
        .orElse(ref);
  }

  /**
   * The chain compile and export share: load with stages 1 and 2, a check that the model has both
   * sides (ADR-0029), the source and target structures, then stage 3 and lowering. The first of
   * these that refuses the model gives {@code refused} its diagnostics, which the result sorts; the
   * structures are both read before either refusal is reported. Stage 3 is the fingerprint check of
   * both sides and lowering, whose diagnostics are reported together (#91). A model that lowers
   * with both fingerprints matching gives {@code lowered} its unoptimised IR, with the model and
   * the target structure it was lowered from.
   */
  private static <R> R loadAndLower(
      byte[] model,
      ReferenceResolver resolver,
      Function<List<Diagnostic>, R> refused,
      Function<LoweredModel, R> lowered)
      throws MalformedJsonException {
    return switch (MappingModelFormat.load(model)) {
      case Refused r -> refused.apply(r.diagnostics());
      case Loaded(MappingModel loaded) -> lower(loaded, resolver, refused, lowered);
    };
  }

  /**
   * {@link #loadAndLower} after a load that passed stages 1 and 2. A model without its source or
   * target is refused with {@code missing-structure} at each side it lacks before any bundle is
   * read, and no further stage runs (ADR-0029).
   */
  private static <R> R lower(
      MappingModel loaded,
      ReferenceResolver resolver,
      Function<List<Diagnostic>, R> refused,
      Function<LoweredModel, R> lowered) {
    List<Diagnostic> refusals = new ArrayList<>();
    missingStructure(loaded.source(), "/source", "source", refusals);
    missingStructure(loaded.target(), "/target", "target", refusals);
    if (!refusals.isEmpty()) {
      return refused.apply(refusals);
    }
    StructureRef sourceRef = loaded.source().orElseThrow();
    StructureRef targetRef = loaded.target().orElseThrow();
    Optional<Structure> source = structure(resolver, sourceRef, refusals);
    Optional<Structure> target = structure(resolver, targetRef, refusals);
    if (!refusals.isEmpty()) {
      return refused.apply(refusals);
    }
    fingerprint(sourceRef, source.orElseThrow(), "/source/fingerprint", refusals);
    fingerprint(targetRef, target.orElseThrow(), "/target/fingerprint", refusals);
    return switch (Lowering.lower(loaded, source.orElseThrow(), target.orElseThrow())) {
      case Refused r -> {
        refusals.addAll(r.diagnostics());
        yield refused.apply(refusals);
      }
      case Lowered(TargetDocument document) ->
          refusals.isEmpty()
              ? lowered.apply(new LoweredModel(loaded, target.orElseThrow(), document))
              : refused.apply(refusals);
    };
  }

  /**
   * Adds {@code missing-structure} at {@code pointer} to {@code refusals} when the model has no
   * {@code side} (ADR-0029).
   */
  private static void missingStructure(
      Optional<StructureRef> ref, String pointer, String side, List<Diagnostic> refusals) {
    if (ref.isEmpty()) {
      refusals.add(
          new Diagnostic(
              Severity.ERROR,
              MappingDiagnostics.MISSING_STRUCTURE,
              new AtPointer(pointer),
              "the mapping has no " + side + " structure"));
    }
  }

  /** A model that lowers, the target structure it was lowered over, and its unoptimised IR. */
  private record LoweredModel(MappingModel model, Structure target, TargetDocument document) {}

  /**
   * Adds {@code fingerprint-mismatch} at {@code pointer} to {@code refusals} when {@code ref}
   * stores another fingerprint than {@code structure}, read from its bundle, gives (ADR-0009). Only
   * refresh re-derives a stored fingerprint; compile never does.
   */
  private static void fingerprint(
      StructureRef ref, Structure structure, String pointer, List<Diagnostic> refusals) {
    String derived = structure.fingerprint();
    if (!derived.equals(ref.fingerprint())) {
      refusals.add(
          new Diagnostic(
              Severity.ERROR,
              MappingDiagnostics.FINGERPRINT_MISMATCH,
              new AtPointer(pointer),
              "the model stores the fingerprint "
                  + ref.fingerprint()
                  + ", but its schema bundle now gives "
                  + derived
                  + ": the structure changed since the model was written"));
    }
  }

  /**
   * The structure {@code ref} names, or empty once every load error of its bundle is added to
   * {@code refusals}.
   */
  private static Optional<Structure> structure(
      ReferenceResolver resolver, StructureRef ref, List<Diagnostic> refusals) {
    return switch (Structures.load(resolver, ref.bundle(), ref.root())) {
      case StructureResult.Loaded(Structure structure) -> Optional.of(structure);
      case StructureResult.Refused(List<Diagnostic> diagnostics) -> {
        refusals.addAll(diagnostics);
        yield Optional.empty();
      }
    };
  }

  /**
   * Runs one input message through a compiled mapping and returns the target message's bytes. A
   * test-run compile's trace is recorded and dropped; {@link #testRun} returns it.
   *
   * @throws MalformedInputException when the input is not well-formed in the mapping's input format
   *     (ADR-0008, #98): XML, without a DOCTYPE, or JSON, without a duplicate key
   * @throws MappingRunException when the run meets a runtime error in its data, with its code and
   *     authoring id (ADR-0015 since #52)
   * @throws java.util.concurrent.CancellationException when the thread is interrupted
   */
  public byte[] run(CompiledMapping mapping, byte[] input) throws MalformedInputException {
    return run(mapping, input, new Tracer());
  }

  /** {@link #run}, reporting to {@code tracer}, at the time the clock gives as it starts. */
  private byte[] run(CompiledMapping mapping, byte[] input, Tracer tracer)
      throws MalformedInputException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      mapping
          .executable()
          .run(new ByteArrayInputStream(input), out, tracer, OffsetDateTime.now(clock));
    } catch (IOException e) {
      // only the output stream can throw it, and a ByteArrayOutputStream never does
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  /**
   * A test run (ADR-0014, "Running"): {@link #run} on a platform thread of its own, never a virtual
   * one (ADR-0020), for at most {@code timeout}. A runtime error in the run's data is the result's
   * runtime error, with its code, message and authoring id (ADR-0015 since #52). Past the timeout
   * the run is interrupted, never stopped, and the result is the runtime error {@code
   * flottila:TIMEOUT}, with no authoring id; the run itself ends at its next interrupt check, in
   * the parse or a slot loop.
   *
   * <p>Each result carries the run's trace (ADR-0015 "Trace" since #53), built here from the
   * tracer's reports, with each entry capped at {@code traceLimit}: the whole trace with the
   * output, the trace up to the error with a runtime error, and at the timeout a snapshot of the
   * reports so far, which the run's later reports never reach (ADR-0014 since #53). A compile
   * without tracing never reports, so its trace is empty.
   *
   * @param mapping a compiled mapping
   * @param input the input message's bytes
   * @param timeout how long the run may take
   * @param traceLimit the trace limit: the most slots, contexts or occurrence records one trace
   *     entry keeps, at least 1
   * @return the target message, or the runtime error that stopped the run
   * @throws IllegalArgumentException when {@code traceLimit} is less than 1; nothing runs
   * @throws MalformedInputException when the input is not well-formed in the mapping's input format
   * @throws CancellationException when the calling thread is interrupted while it waits; its
   *     interrupt status is kept, and the run is interrupted too
   * @throws IllegalStateException when the run fails with an unchecked throwable, its cause
   */
  public TestRunResult testRun(
      CompiledMapping mapping, byte[] input, Duration timeout, int traceLimit)
      throws MalformedInputException {
    if (traceLimit < 1) {
      throw new IllegalArgumentException("a trace limit is at least 1, not " + traceLimit);
    }
    Tracer tracer = new Tracer();
    FutureTask<byte[]> task = new FutureTask<>(() -> run(mapping, input, tracer));
    // a daemon, as a virtual thread is, so that a run which ignores its interrupt never holds
    // the JVM open (ADR-0020)
    Thread.ofPlatform().name("flottila test run").daemon(true).start(task);
    try {
      byte[] output = deadline.await(task, timeout, tracer);
      return new TestRunResult.Output(
          output, TraceBuilder.build(tracer.reports(), mapping.occurrences(), traceLimit));
    } catch (TimeoutException late) {
      // the run reports on until its next interrupt check: first the snapshot, a copy of the
      // reports so far taken under the tracer's lock, then the interrupt, and last the trace,
      // built from the copy (ADR-0014 since #53)
      List<Tracer.Report> soFar = tracer.reports();
      task.cancel(true);
      return new TestRunResult.RuntimeError(
          TIMEOUT,
          "the test run took longer than its timeout of " + timeout.toMillis() + " ms",
          Optional.empty(),
          TraceBuilder.build(soFar, mapping.occurrences(), traceLimit));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new CancellationException("the test run was interrupted while it was awaited");
    } catch (ExecutionException failed) {
      // run declares only MalformedInputException; anything else it throws is unchecked
      switch (failed.getCause()) {
        case MalformedInputException malformed -> throw malformed;
        case MappingRunException error -> {
          return new TestRunResult.RuntimeError(
              error.code(),
              error.getMessage(),
              Optional.of(error.authoringId()),
              TraceBuilder.build(tracer.reports(), mapping.occurrences(), traceLimit));
        }
        default -> throw new IllegalStateException("the test run failed", failed.getCause());
      }
    } finally {
      // a no-op once the run is done or cancelled; otherwise it interrupts the run's thread
      task.cancel(true);
    }
  }
}
