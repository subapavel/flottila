package io.flottila.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-05 done-when: every public type of the api carries {@code @since} (public types are expensive to
 * reverse) — top-level types and the nested ones that are public or, inside an interface, implicitly so.
 */
class PublicTypesHaveSinceTest {

    static final Pattern TOP_LEVEL = Pattern.compile("^public (final |sealed |abstract )*(class|interface|record|enum) (\\w+)", Pattern.MULTILINE);
    /** Indented member types: {@code public …} anywhere, or any member type of an interface (implicitly public). */
    static final Pattern NESTED = Pattern.compile("^[ \\t]+((public |static |final |sealed |abstract )*)(class|interface|record|enum) (\\w+)", Pattern.MULTILINE);

    record Found(String name, int publicCount, List<String> missing) {}

    static Found scan(String text) {
        List<String> missing = new ArrayList<>();
        int count = 0;
        var top = TOP_LEVEL.matcher(text);
        if (!top.find()) return new Found(null, 0, missing);
        count++;
        if (!sinceBefore(text, top.start())) missing.add(top.group(3));
        boolean topIsInterface = top.group(2).equals("interface");
        var nested = NESTED.matcher(text);
        while (nested.find()) {
            boolean isPublic = nested.group(1).contains("public ") || topIsInterface;
            if (!isPublic) continue;
            count++;
            if (!sinceBefore(text, nested.start())) missing.add(top.group(3) + "." + nested.group(4));
        }
        return new Found(top.group(3), count, missing);
    }

    /** Only whitespace and annotations may sit between a type's own Javadoc block and its declaration. */
    private static final Pattern ANNOTATIONS_ONLY = Pattern.compile("(\\s|@\\w+(\\([^)]*\\))?)*");

    private static boolean sinceBefore(String text, int declarationStart) {
        String before = text.substring(0, declarationStart);
        int doc = before.lastIndexOf("/**");
        int end = before.lastIndexOf("*/");
        if (doc < 0 || end < doc) return false;
        boolean ownBlock = ANNOTATIONS_ONLY.matcher(before.substring(end + 2)).matches();
        return ownBlock && before.substring(doc, end).contains("@since");
    }

    @Test
    void scannerSeesNestedPublicTypesAndIgnoresPrivateOnes() {
        String src = """
                /** @since 0.1 */
                public interface Outer {
                    /** nested without since */
                    record Inner(int x) {}
                    /** @since 0.1 */
                    enum Kind { A }
                    @FunctionalInterface
                    interface Undocumented { void run(); }
                    /**
                     * @since 0.1
                     */
                    @Deprecated(since = "0.1")
                    record Annotated(int y) {}
                }
                """;
        Found f = scan(src);
        assertEquals(5, f.publicCount(), "Outer, Inner, Kind, Undocumented, Annotated");
        assertEquals(List.of("Outer.Inner", "Outer.Undocumented"), f.missing(),
                "a nested type without any Javadoc must not inherit the preceding member's block");
        assertEquals(List.of("Plain"), scan("public final class Plain {}").missing(), "no Javadoc at all");
        String cls = """
                /** @since 0.1 */
                public final class Holder {
                    private static final class Hidden {}
                    static final class PackagePrivate {}
                }
                """;
        assertEquals(1, scan(cls).publicCount(), "non-public member types are not api");
    }

    @Test
    void everyPublicTypeDeclaresSince() throws IOException {
        Path src = Path.of("src/main/java/io/flottila/api");
        assertTrue(Files.isDirectory(src), "runs from the module directory: " + src.toAbsolutePath());
        List<String> missing = new ArrayList<>();
        int publicTypes = 0;
        try (Stream<Path> files = Files.walk(src)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java") && !p.endsWith("package-info.java")).toList()) {
                Found found = scan(Files.readString(f));
                publicTypes += found.publicCount();
                missing.addAll(found.missing());
            }
        }
        assertEquals(List.of(), missing, "public types without @since");
        assertEquals(37, publicTypes, "31 top-level + 6 nested public types after P1-07 (BodyKind); update this number with every api change");
    }
}
