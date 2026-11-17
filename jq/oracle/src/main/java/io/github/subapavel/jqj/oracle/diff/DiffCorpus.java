package io.github.subapavel.jqj.oracle.diff;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Every differential case below a directory, with its recording if it has one. The directory is walked recursively,
 * and a file's family is its path below it (ADR-0025): {@code gen/values-v1.cases} holds {@code gen/values-v1/<seed>}.
 */
public final class DiffCorpus {

  /** A case and its recording from the sibling {@code <family>.rec}; null when the case was never recorded. */
  public record Entry(DiffCase c, Recording recording) {}

  private DiffCorpus() {}

  /** The cases of every {@code .cases} file below {@code dir}, sorted by id. */
  public static List<Entry> load(Path dir) {
    List<Entry> out = new ArrayList<>();
    try (Stream<Path> files = Files.walk(dir)) {
      for (Path f : files.filter(p -> p.toString().endsWith(".cases")).toList()) {
        String family = CaseFormat.family(dir, f);
        Map<String, Recording> recordings = recordings(dir.resolve(family + ".rec"), family);
        for (CaseFormat.Block b : CaseFormat.read(f, family)) {
          DiffCase c = DiffCase.of(f.getFileName().toString(), b);
          out.add(new Entry(c, recordings.get(c.id())));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    out.sort(Comparator.comparing(e -> e.c().id()));
    return out;
  }

  private static Map<String, Recording> recordings(Path rec, String family) {
    Map<String, Recording> out = new HashMap<>();
    if (!Files.exists(rec)) return out;
    for (CaseFormat.Block b : CaseFormat.read(rec, family)) out.put(b.id(), Recording.of(rec.getFileName().toString(), b));
    return out;
  }
}
