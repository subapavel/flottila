package io.flottila.mapping.service;

import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import io.flottila.mapping.mapping.MalformedJsonException;
import io.flottila.mapping.runtime.MalformedInputException;
import jakarta.ws.rs.NotSupportedException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.jboss.resteasy.reactive.server.UnwrapException;

/**
 * The transport errors as {@code application/problem+json} (RFC 9457; ADR-0024, "Problems"). Each
 * slug has a fixed status and title.
 *
 * <p>quarkus-rest-jackson wraps a body that is no JSON in a {@link WebApplicationException}, and
 * Jackson wraps a parse error inside a member's value, a duplicate key for one, in a plain {@link
 * JsonMappingException}. No mapper here takes either type or a supertype, so the cause is mapped.
 * quarkus-rest-jackson's own mapper for {@link MismatchedInputException} is disabled in {@code
 * application.properties}.
 */
@UnwrapException({WebApplicationException.class, JsonMappingException.class})
public class ProblemMappers {

  static final String PROBLEM_JSON = "application/problem+json";
  private static final String TYPE = "urn:flottila:mapping:problem:";

  /** A refused path, or a bundle reference the workspace's reference resolver refused (#90). */
  @ServerExceptionMapper({WorkspacePathException.class, InvalidWorkspacePathException.class})
  public Response invalidWorkspacePath(Exception e) {
    return problem(400, "invalid-workspace-path", "Not a workspace path", e.getMessage());
  }

  @ServerExceptionMapper({MalformedJsonException.class, StreamReadException.class})
  public Response malformedJson(Exception e) {
    return problem(400, "malformed-json", "Not a JSON document", e.getMessage());
  }

  @ServerExceptionMapper({MismatchedInputException.class, InvalidRequestException.class})
  public Response invalidRequest(Exception e) {
    return problem(400, "invalid-request", "Not a request of this endpoint", e.getMessage());
  }

  @ServerExceptionMapper
  public Response malformedInput(MalformedInputException e) {
    return problem(400, "malformed-input", "Not a well-formed input message", e.getMessage());
  }

  /** The detail never names the file: it would show the workspace's absolute path. */
  @ServerExceptionMapper
  public Response notFound(NoSuchFileException e) {
    return problem(404, "not-found", "Not found", "no such mapping or directory in the workspace");
  }

  @ServerExceptionMapper
  public Response mappingExists(FileAlreadyExistsException e) {
    return problem(409, "mapping-exists", "Mapping exists", "a mapping with this id exists");
  }

  /** Not ADR-0024's {@code mapping-exists}, which answers a create (ADR-0027). */
  @ServerExceptionMapper
  public Response mappingChanged(MappingChangedException e) {
    return problem(409, "mapping-changed", "Mapping changed", e.getMessage());
  }

  @ServerExceptionMapper
  public Response unsupportedMediaType(NotSupportedException e) {
    return problem(415, "unsupported-media-type", "Unsupported media type", e.getMessage());
  }

  @ServerExceptionMapper
  public Response modelRefused(ModelRefusedException e) {
    return Response.status(422)
        .type(PROBLEM_JSON)
        .entity(
            new RefusedProblem(
                URI.create(TYPE + "model-refused"),
                "Model refused",
                422,
                e.getMessage(),
                e.diagnostics()))
        .build();
  }

  private static Response problem(int status, String slug, String title, String detail) {
    return Response.status(status)
        .type(PROBLEM_JSON)
        .entity(new Problem(URI.create(TYPE + slug), title, status, detail))
        .build();
  }
}
