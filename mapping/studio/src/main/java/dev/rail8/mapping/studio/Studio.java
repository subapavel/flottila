package dev.rail8.mapping.studio;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import dev.rail8.mapping.api.EngineFacade;
import dev.rail8.mapping.mapping.MalformedJsonException;
import dev.rail8.mapping.runtime.MalformedInputException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * The mapping studio's operations on bytes: the answers the browser gets, each a status and its
 * JSON. The studio reads no file, changes nothing and keeps nothing between calls; a call brings
 * the bundle files its request needs.
 *
 * <p>{@code catalogue} takes no call. Every other operation takes a POST call, {@code {"request":
 * "<base64>", "files": {"<bundle reference>": "<base64>"}, "escaping": ["<bundle reference>"]}}:
 *
 * <ul>
 *   <li>{@code canonical}: the request is a model, and the call also {@code "reads": "stored"} or
 *       {@code "sent"}; 200 with its canonical form. A stored model that is no JSON document or is
 *       refused is 422; a sent one is 400 or 422.
 *   <li>{@code create}: the request is a new mapping's; 201 with the new model in canonical form.
 *   <li>{@code structure}: the request is a structure to load; 200 with the structure or its load
 *       error.
 *   <li>{@code compile}: the request is a working copy; 200 with its diagnostics.
 *   <li>{@code refresh}: the request is a working copy; 200 with it refreshed, or 422.
 *   <li>{@code test}: the request is a test run's; 200 with its output, refusal or runtime error.
 * </ul>
 *
 * <p>Bundle files: a reference in {@code files} is read from it; one in {@code escaping} is 400
 * {@code invalid-workspace-path} when the engine reaches it; one in neither is the {@code
 * schema-unreadable} diagnostic, and a package reference is {@code package-reference-unsupported}.
 * A call without {@code files} whose request passes every check that comes before a file is read
 * answers 409 {@code files-needed} with every reference the engine reads, in its order, so a
 * request needs that answer at most once.
 *
 * <p>A refused request answers a problem (RFC 9457) of type {@code
 * urn:rail8:mapping:problem:<slug>}: 400 {@code invalid-workspace-path}, {@code malformed-json},
 * {@code invalid-request} or {@code malformed-input}, 409 {@code files-needed}, or 422 {@code
 * model-refused} with its diagnostics. A call that is not of the studio's shape is 400 {@code
 * invalid-request}.
 *
 * <p>The engine's work runs on the caller's thread, except a test run's, which runs on a platform
 * thread of its own that has ended once {@link #answer} returns. An interrupt of the caller stops a
 * test run with a {@link java.util.concurrent.CancellationException}, and the interrupt is kept.
 */
public final class Studio {

  private Studio() {}

  /** The most slots, contexts or occurrence records one trace entry of a test run keeps. */
  public static final int TRACE_LIMIT = 10_000;

  /** How long a test run may take; past it, the run is the runtime error {@code rail8:TIMEOUT}. */
  public static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);

  /**
   * The most bytes a test run's output may have, 16 MiB; past it, the run is the runtime error
   * {@code rail8:OUTPUT-TOO-LARGE}.
   */
  public static final long MAX_OUTPUT_BYTES = 16L << 20;

  /** The operations, {@code catalogue} first: the only one that takes no call. */
  public static final List<String> OPERATIONS =
      List.of("catalogue", "canonical", "create", "structure", "compile", "test", "refresh");

  private static final String TYPE = "urn:rail8:mapping:problem:";

  /**
   * An answer.
   *
   * @param status the HTTP status
   * @param json the body, a problem for a status of 400 or more
   */
  public record Reply(int status, String json) {}

  /**
   * The answer to a call of {@code op}.
   *
   * @param op one of {@link #OPERATIONS}
   * @param json the call; ignored for {@code catalogue}
   * @param clock the clock a test run reads as it starts; its zone is the run's time zone
   * @throws IllegalArgumentException when {@code op} is none of {@link #OPERATIONS}
   * @throws java.util.concurrent.CancellationException when the thread is interrupted during a test
   *     run; its interrupt status is kept
   */
  public static Reply answer(String op, String json, Clock clock) {
    return answer(op, json, clock, TEST_TIMEOUT, TRACE_LIMIT);
  }

  /** {@link #answer(String, String, Clock)} with a test run's timeout and trace limit. */
  static Reply answer(String op, String json, Clock clock, Duration timeout, int traceLimit) {
    if (!OPERATIONS.contains(op)) {
      throw new IllegalArgumentException("no operation of the studio: " + op);
    }
    EngineFacade facade = new EngineFacade(clock);
    if (op.equals("catalogue")) {
      return new Reply(200, new String(facade.catalogue(), UTF_8));
    }
    Call call;
    try {
      call = Call.read(op, json);
    } catch (InvalidRequestException e) {
      return invalidRequest(e);
    }
    BundleFiles resolver = call.resolver();
    Reply reply = answer(op, call, new Operations(facade, resolver, timeout, traceLimit));
    List<String> needed = resolver.needed();
    if (!call.sentFiles() && !needed.isEmpty()) {
      return new Reply(
          409,
          Json.write(
              new FilesNeeded(
                  TYPE + "files-needed",
                  "Files needed",
                  409,
                  "send the file of each reference in files, or the reference in escaping when it"
                      + " is no workspace path",
                  needed)));
    }
    return reply;
  }

  /** The answer of {@code op} to {@code call}, or the problem that refuses it. */
  private static Reply answer(String op, Call call, Operations operations) {
    byte[] request = call.request();
    try {
      return switch (op) {
        case "canonical" -> canonical(operations, request, call.readsStored());
        case "create" -> model(201, operations.create(request));
        case "structure" -> json(operations.structure(request));
        case "compile" -> json(operations.compile(request));
        case "refresh" -> json(operations.refresh(request));
        case "test" -> json(operations.test(request));
        default -> throw new IllegalArgumentException("no operation of the studio: " + op);
      };
    } catch (InvalidWorkspacePathException e) {
      return problem(400, "invalid-workspace-path", "Not a workspace path", e.getMessage());
    } catch (MalformedJsonException | StreamReadException e) {
      return malformedJson(e);
    } catch (MismatchedInputException e) {
      return invalidRequest(e);
    } catch (InvalidRequestException e) {
      return invalidRequest(e);
    } catch (JsonMappingException e) {
      // Jackson wraps a parse error inside a member's value, a duplicate key for one, in a plain
      // JsonMappingException
      if (e.getCause() instanceof StreamReadException malformed) {
        return malformedJson(malformed);
      }
      throw new IllegalStateException("a request cannot be bound", e);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("a request cannot be read", e);
    } catch (MalformedInputException e) {
      return problem(400, "malformed-input", "Not a well-formed input message", e.getMessage());
    } catch (ModelRefusedException e) {
      return refused(e);
    }
  }

  /** {@code canonical}: a stored model that is no JSON document is refused, with no diagnostics. */
  private static Reply canonical(Operations operations, byte[] model, boolean stored)
      throws MalformedJsonException, ModelRefusedException {
    try {
      return model(200, operations.canonical(model));
    } catch (MalformedJsonException e) {
      if (stored) {
        return refused(new ModelRefusedException(e.getMessage(), List.of()));
      }
      throw e;
    }
  }

  private static Reply model(int status, byte[] canonical) {
    return new Reply(status, new String(canonical, UTF_8));
  }

  private static Reply json(Object answer) {
    return new Reply(200, Json.write(answer));
  }

  private static Reply malformedJson(Exception e) {
    return problem(400, "malformed-json", "Not a JSON document", e.getMessage());
  }

  private static Reply invalidRequest(Exception e) {
    return problem(400, "invalid-request", "Not a request of this endpoint", e.getMessage());
  }

  private static Reply refused(ModelRefusedException e) {
    return new Reply(
        422,
        Json.write(
            new RefusedProblem(
                TYPE + "model-refused", "Model refused", 422, e.getMessage(), e.diagnostics())));
  }

  private static Reply problem(int status, String slug, String title, String detail) {
    return new Reply(status, Json.write(new Problem(TYPE + slug, title, status, detail)));
  }
}
