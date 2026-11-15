package io.flottila.mapping.service;

import static io.flottila.mapping.service.ProblemMappers.PROBLEM_JSON;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.flottila.mapping.api.CompileOptions;
import io.flottila.mapping.api.CompileResult;
import io.flottila.mapping.api.CompiledMapping;
import io.flottila.mapping.api.EngineFacade;
import io.flottila.mapping.api.RefreshResult;
import io.flottila.mapping.api.StructureResult;
import io.flottila.mapping.api.Structures;
import io.flottila.mapping.api.TestRunResult;
import io.flottila.mapping.api.Trace;
import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.mapping.Diagnostic;
import io.flottila.mapping.mapping.Loaded;
import io.flottila.mapping.mapping.MalformedJsonException;
import io.flottila.mapping.mapping.MappingModel;
import io.flottila.mapping.mapping.Refused;
import io.flottila.mapping.mapping.StructureRef;
import io.flottila.mapping.runtime.MalformedInputException;
import io.flottila.mapping.structure.RootSelector;
import io.flottila.mapping.structure.SchemaBundle;
import io.flottila.mapping.structure.Structure;
import io.flottila.mapping.structure.importer.ReferenceResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * {@code /api/mappings} (ADR-0014, ADR-0015, ADR-0024): list, create, open and save the mappings of
 * the workspace, and test-run or refresh a working copy. A saved model's bytes never pass through
 * Jackson: {@code save} reads the body as bytes, so the engine's strict reader sees them as sent,
 * and every answer is the engine's canonical form. {@code refresh} reads its body as bytes too; its
 * answer's {@code model} member is the canonical bytes read into a tree, whose member order Jackson
 * keeps. A test run's working copy is a member of a JSON request body: Jackson parses it into a
 * tree, and {@code test} writes that tree back to bytes with {@code writeValueAsBytes}, so the
 * engine reads Jackson's serialisation, not the bytes as sent.
 */
@jakarta.ws.rs.Path("/api/mappings")
public class MappingsResource {

  /** The mapping model's schema, next to {@code openapi.json} in {@code contract/}. */
  static final String MODEL = "./mapping-model.schema.json";

  static final String ROOT_SELECTOR = MODEL + "#/$defs/rootSelector";

  private static final String ID = "{id: .+}";

  private static final String ETAG = "\"sha256:<hex>\" of the canonical bytes (ADR-0024)";

  /** Held from a conditional save's tag comparison to its write (ADR-0027). */
  private static final Object SAVES = new Object();

  private final Workspace workspace;
  private final ObjectMapper json;

  /** The engine, whose runs take their time in {@code flottila.mapping.time-zone} (#80). */
  private final EngineFacade facade;

  /** {@code flottila.mapping.test-timeout}: how long a test run may take (ADR-0014). */
  private final Duration timeout;

  /** {@code flottila.mapping.trace-limit}: the most one trace entry keeps (ADR-0015, "Trace"). */
  private final int traceLimit;

  @Inject
  MappingsResource(Workspace workspace, ObjectMapper json, Config config, EngineFacade facade) {
    this.workspace = workspace;
    this.json = json;
    this.facade = facade;
    this.timeout = config.getValue("flottila.mapping.test-timeout", Duration.class);
    this.traceLimit = ServiceConfig.traceLimit(config);
  }

  /**
   * The workspace's mappings, sorted by id, each with what its stored model names (ADR-0029). A
   * stored file that cannot be read, is no JSON document or is refused by stage 1 or 2 is listed
   * with its id, name and package alone. Every listed id passes the path checks.
   */
  @GET
  @Produces(APPLICATION_JSON)
  public List<MappingSummary> list() throws IOException, WorkspacePathException {
    List<MappingSummary> summaries = new ArrayList<>();
    for (String id : workspace.list()) {
      summaries.add(MappingSummary.of(id, stored(id)));
    }
    return summaries;
  }

  /** The stored model of {@code id}, or empty when its file is unreadable, no JSON or refused. */
  private Optional<MappingModel> stored(String id) throws WorkspacePathException {
    try {
      return facade.load(workspace.read(id)) instanceof Loaded(MappingModel model)
          ? Optional.of(model)
          : Optional.empty();
    } catch (IOException | MalformedJsonException unreadable) {
      return Optional.empty();
    }
  }

  /**
   * A new mapping with its description and the structures it names, each with its fingerprint, and
   * no target field mapping (ADR-0029): a side it does not name is absent until it is chosen. Its
   * directory must exist; an existing id is 409.
   */
  @POST
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  @APIResponse(
      responseCode = "201",
      description = "The new mapping in canonical form, with its ETag and Location",
      headers = {
        @Header(
            name = "ETag",
            description = ETAG,
            required = true,
            schema = @Schema(type = SchemaType.STRING)),
        @Header(
            name = "Location",
            description = "The new mapping's URI",
            required = true,
            schema = @Schema(type = SchemaType.STRING))
      },
      content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(ref = MODEL)))
  @APIResponse(
      responseCode = "400",
      description = "invalid-workspace-path, malformed-json or invalid-request",
      content =
          @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = Problem.class)))
  @APIResponse(
      responseCode = "404",
      description = "not-found: the mapping's directory",
      content =
          @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = Problem.class)))
  @APIResponse(
      responseCode = "409",
      description = "mapping-exists",
      content =
          @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = Problem.class)))
  @APIResponse(
      responseCode = "415",
      description = "unsupported-media-type",
      content =
          @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = Problem.class)))
  @APIResponse(
      responseCode = "422",
      description =
          "model-refused: a structure cannot be loaded, or the description is over 4,000"
              + " characters",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = RefusedProblem.class)))
  public Response create(CreateMappingRequest request)
      throws IOException,
          WorkspacePathException,
          InvalidRequestException,
          MalformedJsonException,
          ModelRefusedException,
          NoSuchAlgorithmException {
    InvalidRequestException.present(request);
    // a bundle reference that escapes is refused inside the resolver (#90)
    ReferenceResolver resolver = workspace.resolver(workspace.mappingDirectory(request.id()));
    List<Diagnostic> refusals = new ArrayList<>();
    Optional<StructureRef> source = structure(resolver, request.source(), refusals);
    Optional<StructureRef> target = structure(resolver, request.target(), refusals);
    if (!refusals.isEmpty()) {
      throw new ModelRefusedException(
          "a structure of the new mapping cannot be loaded", DiagnosticDto.all(refusals));
    }
    byte[] canonical =
        canonical(facade.save(facade.newMapping(request.description(), source, target)));
    workspace.create(request.id(), canonical);
    return model(Response.created(URI.create("/api/mappings/" + request.id())), canonical);
  }

  /** The stored mapping in canonical form, with its ETag. */
  @GET
  @jakarta.ws.rs.Path(ID)
  @Produces(APPLICATION_JSON)
  @APIResponse(
      responseCode = "200",
      description = "The mapping in canonical form, with its ETag",
      headers =
          @Header(
              name = "ETag",
              description = ETAG,
              required = true,
              schema = @Schema(type = SchemaType.STRING)),
      content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(ref = MODEL)))
  @APIResponse(
      responseCode = "400",
      description = "invalid-workspace-path",
      content =
          @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = Problem.class)))
  @APIResponse(
      responseCode = "404",
      description = "not-found: no mapping of this id",
      content =
          @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = Problem.class)))
  @APIResponse(
      responseCode = "422",
      description = "model-refused: the stored file is no JSON document or no valid model",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = RefusedProblem.class)))
  public Response get(@PathParam("id") String id)
      throws IOException, WorkspacePathException, ModelRefusedException, NoSuchAlgorithmException {
    byte[] stored = workspace.read(id);
    try {
      return model(Response.ok(), canonical(stored));
    } catch (MalformedJsonException e) {
      // a stored file that is no JSON document is refused like a model, with no diagnostics
      throw new ModelRefusedException(e.getMessage(), List.of());
    }
  }

  /**
   * Save (ADR-0015): stages 1 and 2 only, so a stage 3 error still saves. The id and its mapping
   * model file are checked before the body is read. With {@code If-Match} (ADR-0027) the save is
   * conditional: the tag must equal the stored file's canonical ETag, compared as a string, so a
   * weak tag or a list never matches; otherwise 409 {@code mapping-changed} and nothing is written.
   * The tag is compared before the body is parsed (RFC 9110, 13.2.1), and the comparison and the
   * write hold one lock, so two saves with the same tag cannot both pass. Without the header the
   * save is unconditional.
   */
  @PUT
  @jakarta.ws.rs.Path(ID)
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  @APIResponse(
      responseCode = "200",
      description = "The saved mapping in canonical form, with its new ETag",
      headers =
          @Header(
              name = "ETag",
              description = ETAG,
              required = true,
              schema = @Schema(type = SchemaType.STRING)),
      content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(ref = MODEL)))
  @APIResponse(
      responseCode = "400",
      description = "invalid-workspace-path or malformed-json",
      content =
          @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = Problem.class)))
  @APIResponse(
      responseCode = "404",
      description = "not-found: no mapping of this id",
      content =
          @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = Problem.class)))
  @APIResponse(
      responseCode = "409",
      description = "mapping-changed: If-Match differs from the stored file's ETag",
      content =
          @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = Problem.class)))
  @APIResponse(
      responseCode = "415",
      description = "unsupported-media-type",
      content =
          @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = Problem.class)))
  @APIResponse(
      responseCode = "422",
      description = "model-refused: validation stage 1 or 2 refuses the model",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = RefusedProblem.class)))
  public Response save(
      @PathParam("id") String id,
      @Parameter(description = "The ETag the editor read; absent, the save is unconditional")
          @HeaderParam("If-Match")
          String ifMatch,
      @RequestBody(content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(ref = MODEL)))
          InputStream body)
      throws IOException,
          WorkspacePathException,
          MalformedJsonException,
          ModelRefusedException,
          MappingChangedException,
          NoSuchAlgorithmException {
    workspace.existing(id);
    byte[] sent = body.readAllBytes();
    synchronized (SAVES) {
      if (ifMatch != null && !storedEtag(id).equals(Optional.of(ifMatch))) {
        throw new MappingChangedException(
            "the mapping changed on disk since the If-Match tag was read; nothing was saved");
      }
      byte[] canonical = canonical(sent);
      workspace.save(id, canonical);
      return model(Response.ok(), canonical);
    }
  }

  /**
   * The ETag a GET answers for the stored file, or empty when the stored file is refused: no {@code
   * If-Match} can match a file that no GET could tag.
   */
  private Optional<String> storedEtag(String id)
      throws IOException, WorkspacePathException, NoSuchAlgorithmException {
    try {
      return Optional.of(etag(canonical(workspace.read(id))));
    } catch (MalformedJsonException | ModelRefusedException e) {
      return Optional.empty();
    }
  }

  /**
   * A test run of the working copy in the body, never the stored file (ADR-0015). The id only
   * locates the directory its bundle references are relative to.
   */
  @POST
  @jakarta.ws.rs.Path(ID + "/test")
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  @APIResponse(
      responseCode = "200",
      description =
          "The output, the diagnostics that refuse the working copy, or the runtime error",
      content =
          @Content(
              mediaType = APPLICATION_JSON,
              schema = @Schema(implementation = TestResult.class)))
  @APIResponse(
      responseCode = "400",
      description = "invalid-workspace-path, malformed-json, invalid-request or malformed-input",
      content =
          @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = Problem.class)))
  @APIResponse(
      responseCode = "404",
      description = "not-found: the mapping's directory",
      content =
          @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = Problem.class)))
  @APIResponse(
      responseCode = "415",
      description = "unsupported-media-type",
      content =
          @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = Problem.class)))
  public TestResult test(@PathParam("id") String id, TestRequest request)
      throws IOException,
          WorkspacePathException,
          InvalidRequestException,
          MalformedJsonException,
          MalformedInputException,
          JsonProcessingException {
    InvalidRequestException.present(request);
    ReferenceResolver resolver = workspace.resolver(workspace.mappingDirectory(id));
    byte[] model = json.writeValueAsBytes(request.model());
    return switch (facade.load(model)) {
      case Refused(List<Diagnostic> diagnostics) -> new TestRefused(DiagnosticDto.all(diagnostics));
      case Loaded _ -> run(resolver, model, request.input());
    };
  }

  /**
   * Compile (ADR-0015, ADR-0027): the working copy in the body, never the stored file, answers its
   * diagnostics, the {@code unreachable-box} warnings among them (ADR-0011, ADR-0013). A model that
   * stage 1, 2 or 3 refuses is a 200 too; only a transport error is a problem. Nothing is saved.
   * The id only locates the directory the bundle references are relative to.
   */
  @POST
  @jakarta.ws.rs.Path(ID + "/compile")
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  @APIResponse(
      responseCode = "200",
      description = "The working copy's diagnostics, none when it compiles with every box reached",
      content =
          @Content(
              mediaType = APPLICATION_JSON,
              schema = @Schema(implementation = CompileResponse.class)))
  @APIResponse(
      responseCode = "400",
      description = "invalid-workspace-path or malformed-json",
      content =
          @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = Problem.class)))
  @APIResponse(
      responseCode = "404",
      description = "not-found: the mapping's directory",
      content =
          @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = Problem.class)))
  @APIResponse(
      responseCode = "415",
      description = "unsupported-media-type",
      content =
          @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = Problem.class)))
  public CompileResponse compile(
      @PathParam("id") String id,
      @RequestBody(content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(ref = MODEL)))
          InputStream body)
      throws IOException, WorkspacePathException, MalformedJsonException {
    ReferenceResolver resolver = workspace.resolver(workspace.mappingDirectory(id));
    return new CompileResponse(
        DiagnosticDto.all(facade.diagnostics(body.readAllBytes(), resolver)));
  }

  /**
   * Refresh (ADR-0015, ADR-0009 as amended on 2026-11-05): the working copy in the body, never the
   * stored file, comes back in canonical form with the fingerprint of each side it names
   * re-derived, with its orphaned references and every other stage-3 diagnostic once it names both
   * sides (ADR-0029). A side whose bundle fails to load keeps its fingerprint and gives its load
   * diagnostics in the 200. Nothing is saved. The id only locates the directory the bundle
   * references are relative to.
   */
  @POST
  @jakarta.ws.rs.Path(ID + "/refresh")
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  @APIResponse(
      responseCode = "200",
      description = "The refreshed working copy and its diagnostics",
      content =
          @Content(
              mediaType = APPLICATION_JSON,
              schema = @Schema(implementation = RefreshResponse.class)))
  @APIResponse(
      responseCode = "400",
      description = "invalid-workspace-path or malformed-json",
      content =
          @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = Problem.class)))
  @APIResponse(
      responseCode = "404",
      description = "not-found: the mapping's directory",
      content =
          @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = Problem.class)))
  @APIResponse(
      responseCode = "415",
      description = "unsupported-media-type",
      content =
          @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = Problem.class)))
  @APIResponse(
      responseCode = "422",
      description = "model-refused: validation stage 1 or 2 refuses the working copy",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = RefusedProblem.class)))
  public RefreshResponse refresh(
      @PathParam("id") String id,
      @RequestBody(content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(ref = MODEL)))
          InputStream body)
      throws IOException, WorkspacePathException, MalformedJsonException, ModelRefusedException {
    ReferenceResolver resolver = workspace.resolver(workspace.mappingDirectory(id));
    return switch (facade.refresh(body.readAllBytes(), resolver)) {
      case RefreshResult.Refreshed(byte[] model, List<Diagnostic> diagnostics) ->
          new RefreshResponse(json.readTree(model), DiagnosticDto.all(diagnostics));
      case RefreshResult.Refused(List<Diagnostic> diagnostics) ->
          throw new ModelRefusedException(
              "the mapping model is refused by validation stage 1 or 2",
              DiagnosticDto.all(diagnostics));
    };
  }

  /**
   * Compiles the working copy, reading its bundle files through {@code resolver}, which refuses a
   * reference that escapes (#90), and runs it for at most the test-run timeout, with the engine's
   * trace capped at the trace limit.
   */
  private TestResult run(ReferenceResolver resolver, byte[] model, String input)
      throws MalformedJsonException, MalformedInputException {
    return switch (facade.compile(model, resolver, CompileOptions.TEST_RUN)) {
      case CompileResult.Refused(List<Diagnostic> diagnostics) ->
          new TestRefused(DiagnosticDto.all(diagnostics));
      case CompileResult.Compiled(CompiledMapping compiled) ->
          switch (facade.testRun(compiled, input.getBytes(UTF_8), timeout, traceLimit)) {
            case TestRunResult.Output(byte[] output, Trace trace) ->
                new TestOutput(new String(output, UTF_8), List.of(), TraceEntryDto.all(trace));
            case TestRunResult.RuntimeError(
                    String code,
                    String message,
                    Optional<AuthoringId> authoringId,
                    Trace trace) ->
                new TestError(
                    new RuntimeErrorDto(code, message, authoringId.map(AuthoringId::value)),
                    List.of(),
                    TraceEntryDto.all(trace));
          };
    };
  }

  /**
   * The structure {@code chosen} names, or empty when none is chosen or once every load error of
   * its bundle is added to {@code refusals}.
   */
  private static Optional<StructureRef> structure(
      ReferenceResolver resolver, Optional<StructureChoice> chosen, List<Diagnostic> refusals)
      throws InvalidRequestException {
    if (chosen.isEmpty()) {
      return Optional.empty();
    }
    StructureChoice choice = chosen.get();
    SchemaBundle bundle = new SchemaBundle(choice.bundle());
    RootSelector root = RootSelectorJson.read(choice.root());
    return switch (Structures.load(resolver, bundle, root)) {
      case StructureResult.Loaded(Structure structure) ->
          Optional.of(new StructureRef(bundle, root, structure.fingerprint()));
      case StructureResult.Refused(List<Diagnostic> diagnostics) -> {
        refusals.addAll(diagnostics);
        yield Optional.empty();
      }
    };
  }

  /** The canonical form of a model that passes stages 1 and 2 (ADR-0010, ADR-0011). */
  private byte[] canonical(byte[] model) throws MalformedJsonException, ModelRefusedException {
    return switch (facade.load(model)) {
      case Loaded(MappingModel loaded) -> facade.save(loaded);
      case Refused(List<Diagnostic> diagnostics) ->
          throw new ModelRefusedException(
              "the mapping model is refused by validation stage 1 or 2",
              DiagnosticDto.all(diagnostics));
    };
  }

  /** A model answer: the canonical bytes and their ETag (ADR-0024). */
  private static Response model(Response.ResponseBuilder response, byte[] canonical)
      throws NoSuchAlgorithmException {
    return response
        .type(APPLICATION_JSON)
        .header("ETag", etag(canonical))
        .entity(canonical)
        .build();
  }

  /** {@code "sha256:<hex>"} of the canonical bytes, quoted: a strong tag (ADR-0024). */
  private static String etag(byte[] canonical) throws NoSuchAlgorithmException {
    String sha256 =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    return "\"sha256:" + sha256 + "\"";
  }
}
