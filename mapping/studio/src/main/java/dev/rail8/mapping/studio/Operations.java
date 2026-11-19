package dev.rail8.mapping.studio;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.rail8.mapping.api.CompileOptions;
import dev.rail8.mapping.api.CompileResult;
import dev.rail8.mapping.api.CompiledMapping;
import dev.rail8.mapping.api.EngineFacade;
import dev.rail8.mapping.api.RefreshResult;
import dev.rail8.mapping.api.StructureResult;
import dev.rail8.mapping.api.Structures;
import dev.rail8.mapping.api.TestRunResult;
import dev.rail8.mapping.api.Trace;
import dev.rail8.mapping.ir.AuthoringId;
import dev.rail8.mapping.mapping.Diagnostic;
import dev.rail8.mapping.mapping.Loaded;
import dev.rail8.mapping.mapping.MalformedJsonException;
import dev.rail8.mapping.mapping.MappingModel;
import dev.rail8.mapping.mapping.Refused;
import dev.rail8.mapping.mapping.StructureRef;
import dev.rail8.mapping.runtime.MalformedInputException;
import dev.rail8.mapping.structure.RootSelector;
import dev.rail8.mapping.structure.SchemaBundle;
import dev.rail8.mapping.structure.Structure;
import dev.rail8.mapping.structure.importer.ReferenceResolver;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The studio's operations on one request's bytes, over the engine facade, each reading its bundle
 * files through the call's resolver. A model's bytes never pass through Jackson, except a test
 * run's working copy, a member of its request: Jackson parses it into a tree, and {@code test}
 * writes that tree back to bytes, so the engine reads Jackson's serialisation, not the bytes as
 * sent. Every model answer is the engine's canonical form.
 */
final class Operations {

  private final EngineFacade facade;
  private final ReferenceResolver resolver;
  private final Duration timeout;
  private final int traceLimit;

  /**
   * @param timeout how long a test run may take
   * @param traceLimit the most slots, contexts or occurrence records one trace entry keeps
   */
  Operations(EngineFacade facade, ReferenceResolver resolver, Duration timeout, int traceLimit) {
    this.facade = facade;
    this.resolver = resolver;
    this.timeout = timeout;
    this.traceLimit = traceLimit;
  }

  /** The canonical form of a model that passes stages 1 and 2. */
  byte[] canonical(byte[] model) throws MalformedJsonException, ModelRefusedException {
    return switch (facade.load(model)) {
      case Loaded(MappingModel loaded) -> facade.save(loaded);
      case Refused(List<Diagnostic> diagnostics) ->
          throw new ModelRefusedException(
              "the mapping model is refused by validation stage 1 or 2",
              DiagnosticDto.all(diagnostics));
    };
  }

  /**
   * A new mapping with its description and the structures it names, each with its fingerprint, and
   * no target field mapping: a side it does not name is absent until it is chosen. Both root
   * selectors are read before any bundle file.
   */
  byte[] create(byte[] body)
      throws InvalidRequestException,
          JsonProcessingException,
          MalformedJsonException,
          ModelRefusedException {
    CreateMappingRequest request = Json.read(body, CreateMappingRequest.class);
    Optional<StructureChoice> sourceChoice = Optional.ofNullable(request.source());
    Optional<StructureChoice> targetChoice = Optional.ofNullable(request.target());
    Optional<RootSelector> sourceRoot = root(sourceChoice);
    Optional<RootSelector> targetRoot = root(targetChoice);
    List<Diagnostic> refusals = new ArrayList<>();
    Optional<StructureRef> source = structure(sourceChoice, sourceRoot, refusals);
    Optional<StructureRef> target = structure(targetChoice, targetRoot, refusals);
    if (!refusals.isEmpty()) {
      throw new ModelRefusedException(
          "a structure of the new mapping cannot be loaded", DiagnosticDto.all(refusals));
    }
    return canonical(
        facade.save(facade.newMapping(Optional.ofNullable(request.description()), source, target)));
  }

  private static Optional<RootSelector> root(Optional<StructureChoice> chosen)
      throws InvalidRequestException {
    return chosen.isEmpty()
        ? Optional.empty()
        : Optional.of(RootSelectorJson.read(chosen.get().root()));
  }

  /**
   * The structure {@code chosen} names, or empty when none is chosen or once every load error of
   * its bundle is added to {@code refusals}.
   */
  private Optional<StructureRef> structure(
      Optional<StructureChoice> chosen, Optional<RootSelector> root, List<Diagnostic> refusals) {
    if (chosen.isEmpty() || root.isEmpty()) {
      return Optional.empty();
    }
    SchemaBundle bundle = new SchemaBundle(chosen.get().bundle());
    return switch (Structures.load(resolver, bundle, root.get())) {
      case StructureResult.Loaded(Structure structure) ->
          Optional.of(new StructureRef(bundle, root.get(), structure.fingerprint()));
      case StructureResult.Refused(List<Diagnostic> diagnostics) -> {
        refusals.addAll(diagnostics);
        yield Optional.empty();
      }
    };
  }

  /** A structure as a graph, or the load error that refuses it. */
  StructureResponse structure(byte[] body) throws InvalidRequestException, JsonProcessingException {
    StructureRequest request = Json.read(body, StructureRequest.class);
    SchemaBundle bundle = new SchemaBundle(request.bundle());
    return switch (Structures.load(resolver, bundle, RootSelectorJson.read(request.root()))) {
      case StructureResult.Loaded(Structure structure) -> StructureLoaded.of(structure);
      case StructureResult.Refused(List<Diagnostic> diagnostics) ->
          new StructureRefused(DiagnosticDto.all(diagnostics));
    };
  }

  /**
   * Compile: the working copy answers its diagnostics, the {@code unreachable-box} warnings among
   * them. A model that stage 1, 2 or 3 refuses is a 200 too.
   */
  CompileResponse compile(byte[] model) throws MalformedJsonException {
    return new CompileResponse(DiagnosticDto.all(facade.diagnostics(model, resolver)));
  }

  /**
   * Refresh: the working copy comes back in canonical form with the fingerprint of each side it
   * names re-derived, with its orphaned references and every other stage-3 diagnostic once it names
   * both sides. A side whose bundle fails to load keeps its fingerprint and gives its load
   * diagnostics. Its {@code model} member is the canonical bytes read into a tree, whose member
   * order Jackson keeps.
   */
  RefreshResponse refresh(byte[] model) throws MalformedJsonException, ModelRefusedException {
    return switch (facade.refresh(model, resolver)) {
      case RefreshResult.Refreshed(byte[] refreshed, List<Diagnostic> diagnostics) -> {
        try {
          yield new RefreshResponse(
              Json.MAPPER.readTree(refreshed), DiagnosticDto.all(diagnostics));
        } catch (IOException e) {
          throw new IllegalStateException("the engine's canonical form is no JSON document", e);
        }
      }
      case RefreshResult.Refused(List<Diagnostic> diagnostics) ->
          throw new ModelRefusedException(
              "the mapping model is refused by validation stage 1 or 2",
              DiagnosticDto.all(diagnostics));
    };
  }

  /**
   * A test run of the working copy in the request, for at most the timeout, with each trace entry
   * capped at the trace limit and the output at {@link Studio#MAX_OUTPUT_BYTES}. The run sees the
   * facade's clock and its zone.
   */
  TestResult test(byte[] body)
      throws InvalidRequestException,
          JsonProcessingException,
          MalformedJsonException,
          MalformedInputException {
    TestRequest request = Json.read(body, TestRequest.class);
    byte[] model = Json.MAPPER.writeValueAsBytes(request.model());
    return switch (facade.load(model)) {
      case Refused(List<Diagnostic> diagnostics) -> new TestRefused(DiagnosticDto.all(diagnostics));
      case Loaded _ -> run(model, request.input());
    };
  }

  private TestResult run(byte[] model, String input)
      throws MalformedJsonException, MalformedInputException {
    return switch (facade.compile(model, resolver, CompileOptions.TEST_RUN)) {
      case CompileResult.Refused(List<Diagnostic> diagnostics) ->
          new TestRefused(DiagnosticDto.all(diagnostics));
      case CompileResult.Compiled(CompiledMapping compiled) ->
          switch (facade.testRun(
              compiled, input.getBytes(UTF_8), timeout, traceLimit, Studio.MAX_OUTPUT_BYTES)) {
            case TestRunResult.Output(byte[] output, Trace trace) ->
                new TestOutput(new String(output, UTF_8), List.of(), TraceEntryDto.all(trace));
            case TestRunResult.RuntimeError(
                    String code,
                    String message,
                    Optional<AuthoringId> authoringId,
                    Trace trace) ->
                new TestError(
                    new RuntimeErrorDto(
                        code, message, authoringId.map(AuthoringId::value).orElse(null)),
                    List.of(),
                    TraceEntryDto.all(trace));
          };
    };
  }
}
