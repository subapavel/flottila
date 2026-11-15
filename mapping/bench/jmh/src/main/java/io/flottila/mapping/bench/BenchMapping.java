package io.flottila.mapping.bench;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.flottila.mapping.api.CompileOptions;
import io.flottila.mapping.api.CompileResult;
import io.flottila.mapping.api.CompiledMapping;
import io.flottila.mapping.api.EngineFacade;
import io.flottila.mapping.mapping.Diagnostic;
import io.flottila.mapping.mapping.MalformedJsonException;
import io.flottila.mapping.structure.importer.ReferenceResolver;
import io.flottila.mapping.structure.importer.ResolvedFile;
import io.flottila.mapping.structure.importer.UnresolvedReferenceException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import orders.OrdersGenerator;

/**
 * A bench mapping of {@code corpus/bench/<name>/} (ADR-0021), read from the class path, where the
 * bench module packs that directory: the model {@code <name>.mapping.json}, the schema files beside
 * it, and the seeded generator with the seed and the two sizes of {@code bench.json}.
 */
final class BenchMapping {

  /** A bench mapping's seeded generator: an input of at least {@code size} bytes. */
  @FunctionalInterface
  interface Generator {
    byte[] generate(long seed, int size);
  }

  private final String name;
  private final Generator generator;
  private final long seed;
  private final int smallBytes;
  private final int largeBytes;
  private final byte[] model;

  /** The schema files read so far: after the first compile, a compile reads only memory. */
  private final Map<String, byte[]> files = new ConcurrentHashMap<>();

  private BenchMapping(
      String name, Generator generator, long seed, int smallBytes, int largeBytes, byte[] model) {
    this.name = name;
    this.generator = generator;
    this.seed = seed;
    this.smallBytes = smallBytes;
    this.largeBytes = largeBytes;
    this.model = model;
  }

  /** The {@code orders} bench mapping. */
  static BenchMapping orders() {
    return of("orders", OrdersGenerator::generate);
  }

  /**
   * The bench mapping in the class-path directory {@code name}.
   *
   * @throws IllegalStateException when the directory has no {@code bench.json} or no model
   */
  static BenchMapping of(String name, Generator generator) {
    String bench = new String(required(name, "bench.json"), UTF_8);
    return new BenchMapping(
        name,
        generator,
        number(bench, "seed"),
        Math.toIntExact(number(bench, "smallBytes")),
        Math.toIntExact(number(bench, "largeBytes")),
        required(name, name + ".mapping.json"));
  }

  /**
   * The number {@code member} of {@code bench.json}, whose members are numbers with distinct names.
   *
   * @throws IllegalStateException when it has no such member
   */
  static long number(String bench, String member) {
    Matcher m = Pattern.compile("\"" + member + "\"\\s*:\\s*(\\d+)").matcher(bench);
    if (!m.find()) {
      throw new IllegalStateException("bench.json has no number " + member);
    }
    return Long.parseLong(m.group(1));
  }

  /** The model's bytes. */
  byte[] model() {
    return model;
  }

  /** The small input (8-10 kB), from the seed. */
  byte[] small() {
    return generator.generate(seed, smallBytes);
  }

  /** The large input (at least 10,485,760 bytes), from the seed; generated, never committed. */
  byte[] large() {
    return generator.generate(seed, largeBytes);
  }

  /**
   * The resolver of the model's schema bundle references: the files beside the model, held in
   * memory once read. Any other reference is {@code UNREADABLE}.
   */
  ReferenceResolver resolver() {
    return reference -> {
      byte[] content = files.get(reference);
      if (content == null) {
        content = resource(name, reference);
        if (content == null) {
          throw new UnresolvedReferenceException(
              UnresolvedReferenceException.Reason.UNREADABLE, reference);
        }
        files.put(reference, content);
      }
      return new ResolvedFile(reference, content);
    };
  }

  /**
   * The model compiled by {@code facade} with {@code options}, which fills the resolver's memory.
   *
   * @throws IllegalStateException when the model is refused: a benchmark never times a refusal
   */
  CompiledMapping compile(EngineFacade facade, CompileOptions options)
      throws MalformedJsonException {
    return compiled(facade.compile(model, resolver(), options), name);
  }

  /**
   * The compiled mapping of {@code result}.
   *
   * @throws IllegalStateException when it is refused
   */
  static CompiledMapping compiled(CompileResult result, String name) {
    return switch (result) {
      case CompileResult.Compiled(CompiledMapping mapping) -> mapping;
      case CompileResult.Refused(List<Diagnostic> diagnostics) ->
          throw new IllegalStateException(
              "the bench mapping " + name + " is refused: " + diagnostics);
    };
  }

  private static byte[] required(String name, String file) {
    byte[] content = resource(name, file);
    if (content == null) {
      throw new IllegalStateException("no class-path resource " + name + "/" + file);
    }
    return content;
  }

  /** The bytes of the class-path resource {@code name/file}, or {@code null} when it is absent. */
  private static byte[] resource(String name, String file) {
    try (InputStream in = BenchMapping.class.getResourceAsStream("/" + name + "/" + file)) {
      return in == null ? null : in.readAllBytes();
    } catch (IOException e) {
      // a resource that opened and then failed to read: the jar or the class path is broken
      throw new UncheckedIOException(e);
    }
  }
}
