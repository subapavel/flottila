package io.flottila.mapping.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every corpus case whose provenance is {@code assumed} is waiting for an owner's observation in
 * SAP PO (ADR-0018 "Observed cases"). A case missing from every observation request would never be
 * observed. A case counts only when a {@code request.md} under {@code corpus/observations} has its
 * own case-table row, the id in the first cell and the input file in the second, and that file
 * exists in the request's directory and holds the case's input. A mention of the id in another
 * case's text does not count.
 */
class ObservationRequestsCoverAssumedCasesTest {

  private static final Path OBSERVATIONS = CaseFiles.corpus().resolve("observations");

  private static List<Path> requests() throws IOException {
    try (Stream<Path> all = Files.walk(OBSERVATIONS)) {
      return all.filter(p -> p.getFileName().toString().equals("request.md")).sorted().toList();
    }
  }

  /** The input file names in the rows of {@code request} whose first cell is {@code caseId}. */
  static List<String> rowInputs(String request, String caseId) {
    Pattern row =
        Pattern.compile(
            "^\\|\\s*`" + Pattern.quote(caseId) + "`\\s*\\|\\s*`([^`]+)`\\s*\\|",
            Pattern.MULTILINE);
    List<String> files = new ArrayList<>();
    Matcher m = row.matcher(request);
    while (m.find()) {
      files.add(m.group(1));
    }
    return files;
  }

  private static String normalised(Path xml) throws IOException {
    return Files.readString(xml, StandardCharsets.UTF_8).replaceAll("\\s+", "");
  }

  @Test
  void rowsMatchWholeIdsOnly() {
    assertEquals(List.of("a.xml"), rowInputs("| `fn/sort/001` | `a.xml` | q |", "fn/sort/001"));
    assertEquals(List.of(), rowInputs("| `fn/sort/0011` | `a.xml` | q |", "fn/sort/001"));
    assertEquals(List.of(), rowInputs("| `xfn/sort/001` | `a.xml` | q |", "fn/sort/001"));
    assertEquals(List.of(), rowInputs("| `fn/sort/001/x` | `a.xml` | q |", "fn/sort/001"));
  }

  @Test
  void anIdOnlyInProseIsMissing() {
    String prose =
        "| `fn/sort/003` | `b.xml` | like `fn/sort/001` but folded |\n"
            + "The input of fn/sort/001 is b.xml.\n";
    assertEquals(List.of(), rowInputs(prose, "fn/sort/001"));
    assertEquals(List.of("b.xml"), rowInputs(prose, "fn/sort/003"));
  }

  @Test
  void everyAssumedCaseHasARowWithItsInput() throws IOException {
    List<CaseFiles.CorpusCase> assumed =
        CaseFiles.all().stream().filter(c -> c.provenance().equals("assumed")).toList();
    assertFalse(assumed.isEmpty(), "no assumed cases: the check would pass for nothing");

    List<Path> requests = requests();
    List<String> missing = new ArrayList<>();
    List<String> noInput = new ArrayList<>();
    for (CaseFiles.CorpusCase c : assumed) {
      String input = normalised(c.input());
      boolean listed = false;
      boolean ready = false;
      for (Path r : requests) {
        for (String name : rowInputs(Files.readString(r, StandardCharsets.UTF_8), c.id())) {
          listed = true;
          Path file = r.getParent().resolve(name);
          ready |= Files.isRegularFile(file) && normalised(file).equals(input);
        }
      }
      if (!listed) {
        missing.add(c.id());
      } else if (!ready) {
        noInput.add(c.id());
      }
    }
    assertEquals(List.of(), missing, "assumed cases without a row in any observation request");
    assertEquals(List.of(), noInput, "assumed cases whose row names no input file of the kit");
  }
}
