package dev.rail8.mapping.service;

import static dev.rail8.mapping.service.ProblemMappers.PROBLEM_JSON;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

import dev.rail8.mapping.api.StructureResult;
import dev.rail8.mapping.api.Structures;
import dev.rail8.mapping.mapping.Diagnostic;
import dev.rail8.mapping.structure.SchemaBundle;
import dev.rail8.mapping.structure.Structure;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Produces;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * {@code POST /api/structures}: a structure's graph and fingerprint, or its load error as a 200
 * {@code refused}.
 */
@jakarta.ws.rs.Path("/api/structures")
public class StructuresResource {

  private final Workspace workspace;

  @Inject
  StructuresResource(Workspace workspace) {
    this.workspace = workspace;
  }

  @POST
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  @APIResponse(
      responseCode = "200",
      description = "The structure, or the load error that refuses it",
      content =
          @Content(
              mediaType = APPLICATION_JSON,
              schema = @Schema(implementation = StructureResponse.class)))
  @APIResponse(
      responseCode = "400",
      description = "invalid-workspace-path, malformed-json or invalid-request",
      content =
          @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = Problem.class)))
  @APIResponse(
      responseCode = "404",
      description = "not-found: the base directory",
      content =
          @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = Problem.class)))
  @APIResponse(
      responseCode = "415",
      description = "unsupported-media-type",
      content =
          @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = Problem.class)))
  public StructureResponse load(StructureRequest request)
      throws IOException, WorkspacePathException, InvalidRequestException {
    InvalidRequestException.present(request);
    Path base = workspace.directory(request.base());
    SchemaBundle bundle = new SchemaBundle(request.bundle());
    // a bundle reference that escapes is refused inside the resolver
    return switch (Structures.load(
        workspace.resolver(base), bundle, RootSelectorJson.read(request.root()))) {
      case StructureResult.Loaded(Structure structure) -> StructureLoaded.of(structure);
      case StructureResult.Refused(List<Diagnostic> diagnostics) ->
          new StructureRefused(DiagnosticDto.all(diagnostics));
    };
  }
}
