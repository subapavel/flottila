package dev.rail8.mapping.studio;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Calls of the studio as the node makes them, in process. A POST call is sent first without files;
 * when the studio answers {@code files-needed}, the call is sent once more with the file of each
 * reference the workspace has and, in {@code escaping}, each reference that is no workspace path,
 * and that answer is final: a second {@code files-needed} fails the test. Every answer the browser
 * gets is checked against the browser's contract.
 */
final class Calls {

  /** The clock of the calls: 2026-11-04 09:00 in UTC. */
  static final Clock UTC = Clock.fixed(Instant.parse("2026-11-04T09:00:00Z"), ZoneOffset.UTC);

  private static final String FILES_NEEDED = Wire.PROBLEM_TYPE + "files-needed";

  private final Contract contract;
  private final Clock clock;
  private final Duration timeout;
  private final int traceLimit;

  private Calls(Contract contract, Clock clock, Duration timeout, int traceLimit) {
    this.contract = contract;
    this.clock = clock;
    this.timeout = timeout;
    this.traceLimit = traceLimit;
  }

  /** Calls at {@link #UTC}, with the studio's own test timeout and trace limit. */
  static Calls studio() {
    return new Calls(Contract.shared(), UTC, Studio.TEST_TIMEOUT, Studio.TRACE_LIMIT);
  }

  /**
   * These calls, each answer also checked against {@code contract}, which notes what it reached.
   */
  Calls checkedBy(Contract contract) {
    return new Calls(contract, clock, timeout, traceLimit);
  }

  /** These calls at {@code clock}. */
  Calls at(Clock clock) {
    return new Calls(contract, clock, timeout, traceLimit);
  }

  /** These calls with a test run's {@code timeout}. */
  Calls timeout(Duration timeout) {
    return new Calls(contract, clock, timeout, traceLimit);
  }

  /** These calls with a test run's {@code traceLimit}. */
  Calls traceLimit(int traceLimit) {
    return new Calls(contract, clock, timeout, traceLimit);
  }

  /**
   * An answer, as the browser gets it.
   *
   * @param operation the studio's operation, {@code canonical} with what it reads
   * @param status the HTTP status
   * @param json the body
   * @param rounds how many calls it took: 1, or 2 after {@code files-needed}
   * @param needed the references of the {@code files-needed} answer, empty when there was none
   */
  record Answer(String operation, int status, String json, int rounds, List<String> needed) {

    /** The body as a tree, read strictly. */
    JsonNode body() {
      return Wire.read(json.getBytes(UTF_8));
    }

    /** The body's bytes. */
    byte[] bytes() {
      return json.getBytes(UTF_8);
    }

    @Override
    public String toString() {
      return operation + " " + status + " after " + rounds + " call(s): " + json;
    }
  }

  /** {@code catalogue}. */
  Answer catalogue() {
    Studio.Reply reply = Studio.answer("catalogue", "", clock, timeout, traceLimit);
    return checked(
        "catalogue", new Answer("catalogue", reply.status(), reply.json(), 1, List.of()));
  }

  /** {@code canonical} of a stored model; it reads no bundle file. */
  Answer stored(byte[] model) {
    return post("canonical", "stored", model, Workspace.empty());
  }

  /** {@code canonical} of a model the browser sent; it reads no bundle file. */
  Answer sent(byte[] model) {
    return post("canonical", "sent", model, Workspace.empty());
  }

  /** {@code create}, {@code structure}, {@code compile}, {@code test} or {@code refresh}. */
  Answer post(String op, byte[] request, Workspace workspace) {
    return post(op, null, request, workspace);
  }

  /** {@link #post(String, byte[], Workspace)} of a UTF-8 request. */
  Answer post(String op, String request, Workspace workspace) {
    return post(op, request.getBytes(UTF_8), workspace);
  }

  private Answer post(String op, String reads, byte[] request, Workspace workspace) {
    String operation = reads == null ? op : op + " " + reads;
    ObjectNode call = JsonNodeFactory.instance.objectNode();
    call.put("request", Base64.getEncoder().encodeToString(request));
    if (reads != null) {
      call.put("reads", reads);
    }
    Studio.Reply first = Studio.answer(op, call.toString(), clock, timeout, traceLimit);
    Optional<List<String>> needed = filesNeeded(first);
    if (needed.isEmpty()) {
      return checked(operation, new Answer(operation, first.status(), first.json(), 1, List.of()));
    }
    ObjectNode files = call.putObject("files");
    List<String> escaping = new ArrayList<>();
    for (String reference : needed.get()) {
      if (workspace.escapes(reference)) {
        escaping.add(reference);
      } else {
        workspace
            .file(reference)
            .ifPresent(
                content -> files.put(reference, Base64.getEncoder().encodeToString(content)));
      }
    }
    escaping.forEach(call.putArray("escaping")::add);
    Studio.Reply second = Studio.answer(op, call.toString(), clock, timeout, traceLimit);
    assertThat(filesNeeded(second))
        .as("a second files-needed of %s: %s", operation, second.json())
        .isEmpty();
    return checked(
        operation, new Answer(operation, second.status(), second.json(), 2, needed.get()));
  }

  /** The references of a {@code files-needed} answer, checked for its shape. */
  private static Optional<List<String>> filesNeeded(Studio.Reply reply) {
    if (reply.status() != 409) {
      return Optional.empty();
    }
    JsonNode problem = Wire.read(reply.json().getBytes(UTF_8));
    assertThat(problem.path("type").textValue()).as("%s", reply).isEqualTo(FILES_NEEDED);
    assertThat(Wire.names(problem))
        .as("members of %s", reply)
        .containsExactly("type", "title", "status", "detail", "references");
    assertThat(problem.path("title").textValue()).isEqualTo("Files needed");
    assertThat(problem.path("status").isInt()).isTrue();
    assertThat(problem.path("status").intValue()).isEqualTo(409);
    assertThat(problem.path("detail").textValue()).isNotBlank();
    JsonNode references = problem.path("references");
    assertThat(references.isArray() && !references.isEmpty()).as("%s", reply).isTrue();
    List<String> needed = new ArrayList<>();
    for (JsonNode reference : references) {
      assertThat(reference.isTextual()).as("%s", reply).isTrue();
      needed.add(reference.textValue());
    }
    assertThat(needed).as("references of %s", reply).doesNotHaveDuplicates();
    return Optional.of(needed);
  }

  private Answer checked(String operation, Answer answer) {
    assertThat(contract.violations(operation, answer.status(), answer.json()))
        .as("how %s breaks the browser's contract", answer)
        .isEmpty();
    return answer;
  }
}
