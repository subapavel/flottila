package io.rail8.archtest;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * P0-09: the milestone-1 module and its Mutiny / Vert.x 4 stack are gone from the build. Reads the
 * repository's poms (the archtest runs from its module directory, platform/archtest; the repository root is two up).
 */
class BuildHygieneTest {

    static final Path ROOT = Path.of("../..").toAbsolutePath().normalize();
    /**
     * Not walked, by name (D-149): the mapping service is a Quarkus application, not the pipeline, and Quarkus brings
     * Mutiny; the rule holds for every other area of the repository, a new one included.
     */
    static final Path MAPPING = ROOT.resolve("mapping");

    @Test
    void theMilestoneOneModuleIsGone() throws IOException {
        String rootPom = Files.readString(ROOT.resolve("pom.xml"));
        assertFalse(rootPom.contains("<module>flottila-m1</module>"), "root pom still lists flottila-m1");
        String platformPom = Files.readString(ROOT.resolve("platform/pom.xml"));   // the parent, whose module list it was
        assertFalse(platformPom.contains("<module>flottila-m1</module>"), "platform pom still lists flottila-m1");
        assertFalse(Files.exists(ROOT.resolve("flottila-m1")), "flottila-m1 directory still exists");
    }

    @Test
    void noMutinyOrVertx4OnAnyModulesBuild() throws IOException {
        List<String> offenders = new ArrayList<>();
        // depth 5: the deepest poms today, platform/{components,steps}/<module>/pom.xml, sit at depth 4; one level of margin
        // so that a module nested one area deeper is still read instead of silently skipped
        try (Stream<Path> poms = Files.walk(ROOT, 5)) {
            for (Path pom : poms.filter(p -> p.getFileName().toString().equals("pom.xml") && !p.toString().contains("/target/")
                    && !p.startsWith(MAPPING)).toList()) {
                String text = Files.readString(pom);
                if (text.contains("io.smallrye") || text.contains("mutiny") || text.contains("<vertx4.version>")) {
                    offenders.add(ROOT.relativize(pom).toString());
                }
            }
        }
        assertEquals(List.of(), offenders, "poms still referencing Mutiny or Vert.x 4");
    }

    @Test
    void theArchtestClasspathCarriesNoMutinyJar() {
        String cp = System.getProperty("java.class.path");
        assertFalse(cp.contains("smallrye-mutiny") || cp.contains("/mutiny-"), "Mutiny on the archtest classpath: " + cp);
        assertFalse(cp.contains("vertx-core-4."), "Vert.x 4 on the archtest classpath");
    }
}
