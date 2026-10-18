package io.flottila.archtest;

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
 * reactor's poms (the archtest runs from its module directory, the repository root is one up).
 */
class BuildHygieneTest {

    static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    @Test
    void theMilestoneOneModuleIsGone() throws IOException {
        String rootPom = Files.readString(ROOT.resolve("pom.xml"));
        assertFalse(rootPom.contains("<module>flottila-m1</module>"), "root pom still lists flottila-m1");
        assertFalse(Files.exists(ROOT.resolve("flottila-m1")), "flottila-m1 directory still exists");
    }

    @Test
    void noMutinyOrVertx4OnAnyModulesBuild() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> poms = Files.walk(ROOT, 4)) {
            for (Path pom : poms.filter(p -> p.getFileName().toString().equals("pom.xml") && !p.toString().contains("/target/")).toList()) {
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
