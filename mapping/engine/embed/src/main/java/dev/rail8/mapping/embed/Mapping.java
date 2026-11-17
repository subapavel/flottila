package dev.rail8.mapping.embed;

import dev.rail8.mapping.api.CompileOptions;
import dev.rail8.mapping.api.CompileResult;
import dev.rail8.mapping.api.CompiledMapping;
import dev.rail8.mapping.api.EngineFacade;
import dev.rail8.mapping.mapping.AtAuthoringId;
import dev.rail8.mapping.mapping.AtFilePosition;
import dev.rail8.mapping.mapping.AtPointer;
import dev.rail8.mapping.mapping.Diagnostic;
import dev.rail8.mapping.mapping.MalformedJsonException;
import dev.rail8.mapping.runtime.MalformedInputException;
import dev.rail8.mapping.runtime.MappingRunException;
import dev.rail8.mapping.structure.importer.ReferenceResolver;
import dev.rail8.mapping.structure.importer.ResolvedFile;
import dev.rail8.mapping.structure.importer.UnresolvedReferenceException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Serial;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A compiled mapping. It is immutable, and any number of runs may use it at the same time.
 *
 * <p>Memory: the host holds the input, the output is at most a run's {@code maxOutputBytes}, and
 * the engine's working memory grows with the input and the mapping, with no limit of its own.
 */
public final class Mapping {

  /** The code of a mapping file whose bytes are no JSON document. */
  static final String MALFORMED_JSON = "malformed-json";

  /** A package reference: {@code name@version:/path}. */
  private static final Pattern PACKAGED =
      Pattern.compile(
          "([A-Za-z0-9_-][A-Za-z0-9._-]*)@([A-Za-z0-9][A-Za-z0-9.+-]*):/"
              + "([A-Za-z0-9_-][A-Za-z0-9._-]*(?:/[A-Za-z0-9_-][A-Za-z0-9._-]*)*)");

  /** The facade compile and run go through; it holds no state, and its clock is never read. */
  private static final EngineFacade ENGINE = new EngineFacade();

  private final CompiledMapping compiled;

  private Mapping(CompiledMapping compiled) {
    this.compiled = compiled;
  }

  /**
   * Compiles a mapping as a deploy does, optimised and untraced: load, both structures, the
   * fingerprint check, lowering. It calls {@code files} on the calling thread, once for each
   * reference a schema bundle lists, the source's first, and for nothing else.
   *
   * @param model the bytes of the mapping file
   * @param files the host's reader of the schema files
   * @return the compiled mapping
   * @throws MappingRefusedException with every problem of the first stage that refuses the mapping:
   *     bytes that are no JSON ({@code malformed-json}), the model's own checks, a missing
   *     structure, a schema load error ({@code schema-unreadable} where {@code files} throws an
   *     {@link IOException}), {@code fingerprint-mismatch}, the checks against both structures, and
   *     every UDF box the mapping reaches ({@code unsupported-box-kind})
   */
  public static Mapping compile(byte[] model, BundleFiles files) throws MappingRefusedException {
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(files, "files");
    ReferenceResolver resolver =
        reference -> {
          try {
            return new ResolvedFile(reference, files.read(parse(reference)));
          } catch (IOException unreadable) {
            throw new UnresolvedReferenceException(
                UnresolvedReferenceException.Reason.UNREADABLE, reference);
          }
        };
    CompileResult result;
    try {
      result = ENGINE.compile(model, resolver, CompileOptions.DEPLOY);
    } catch (MalformedJsonException e) {
      throw new MappingRefusedException(
          List.of(new MappingRefusedException.Problem(MALFORMED_JSON, "", e.getMessage())));
    }
    return switch (result) {
      case CompileResult.Compiled(CompiledMapping mapping) -> new Mapping(mapping);
      case CompileResult.Refused(List<Diagnostic> diagnostics) ->
          throw new MappingRefusedException(diagnostics.stream().map(Mapping::problem).toList());
    };
  }

  /** The media type of every output: {@code application/xml}, as the engine writes XML only. */
  public String mediaType() {
    return "application/xml";
  }

  /**
   * Runs the mapping on one message. It reads all of {@code input} before it writes, and returns
   * the whole output or throws, never part of it. It reads no clock and no file.
   *
   * @param input the message: XML without a DOCTYPE, or JSON, as the source structure says
   * @param maxOutputBytes the most bytes the output may have, at least 1
   * @param now the time every date function sees; its offset is the run's time zone
   * @return the output, at most {@code maxOutputBytes} long
   * @throws RunFailure when the input is malformed, the run meets a data error, or the output
   *     passes {@code maxOutputBytes}
   * @throws InterruptedException when the thread is interrupted before or during the run; as with
   *     any {@link InterruptedException}, the thread's interrupt status is cleared
   * @throws IllegalArgumentException when {@code maxOutputBytes} is less than 1; nothing runs
   */
  public byte[] run(byte[] input, long maxOutputBytes, OffsetDateTime now)
      throws RunFailure, InterruptedException {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(now, "now");
    if (maxOutputBytes < 1) {
      throw new IllegalArgumentException(
          "a run's output limit is at least 1 byte, not " + maxOutputBytes);
    }
    BoundedOutput out = new BoundedOutput(maxOutputBytes);
    try {
      ENGINE.run(compiled, input, out, now);
    } catch (MalformedInputException e) {
      throw new RunFailure(RunFailure.Reason.MALFORMED_INPUT, e.getMessage(), null, null, e);
    } catch (MappingRunException e) {
      throw new RunFailure(
          RunFailure.Reason.DATA_ERROR, e.getMessage(), e.code(), e.authoringId().value(), e);
    } catch (OutputTooLarge e) {
      throw new RunFailure(RunFailure.Reason.OUTPUT_TOO_LARGE, e.getMessage(), null, null, e);
    } catch (CancellationException e) {
      // the engine stops at an interrupt check and leaves the status set
      Thread.interrupted();
      InterruptedException interrupted = new InterruptedException(e.getMessage());
      interrupted.initCause(e);
      throw interrupted;
    } catch (IOException e) {
      // the output holds its bytes in memory and never throws one
      throw new IllegalStateException("the mapping's output failed", e);
    }
    return out.toByteArray();
  }

  /** {@code reference} in the spelling the mapping file writes it. */
  static BundleReference parse(String reference) {
    Matcher packaged = PACKAGED.matcher(reference);
    if (packaged.matches()) {
      return new BundleReference.Packaged(
          reference, packaged.group(1), packaged.group(2), packaged.group(3));
    }
    return new BundleReference.Relative(reference);
  }

  /** The problem a diagnostic reports. */
  private static MappingRefusedException.Problem problem(Diagnostic diagnostic) {
    String at =
        switch (diagnostic.location()) {
          case AtPointer p -> p.pointer();
          case AtAuthoringId a -> a.id().value();
          case AtFilePosition f -> f.file() + ":" + f.line() + ":" + f.column();
        };
    return new MappingRefusedException.Problem(diagnostic.code(), at, diagnostic.message());
  }

  /** A run's output in memory, which refuses to grow past its limit. */
  private static final class BoundedOutput extends ByteArrayOutputStream {

    private final long limit;

    BoundedOutput(long limit) {
      this.limit = limit;
    }

    @Override
    public void write(int b) {
      room(1);
      super.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) {
      room(len);
      super.write(b, off, len);
    }

    /** Refuses a write of {@code len} more bytes that would pass the limit. */
    private void room(int len) {
      if (len > limit - count) {
        throw new OutputTooLarge(limit);
      }
    }
  }

  /** An output that passed its limit; unchecked here, it leaves the run through its writer. */
  private static final class OutputTooLarge extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    OutputTooLarge(long limit) {
      super("the mapping's output is larger than its limit of " + limit + " bytes");
    }
  }
}
