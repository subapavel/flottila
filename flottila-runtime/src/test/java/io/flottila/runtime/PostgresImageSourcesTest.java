package io.flottila.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.flottila.testkit.PostgresImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-169 (D-129): the Postgres version has one source, {@code io.flottila.testkit.PostgresImage}. No module's {@code src/}
 * names a {@code postgres:<digit>} image other than that class, and every compose file of the repository runs exactly its
 * pinned tag, with the volume where the 18 image keeps its data. Checked as documents; no Docker is needed.
 */
class PostgresImageSourcesTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path CONSTANT =
            Path.of("flottila-testkit", "src", "main", "java", "io", "flottila", "testkit", "PostgresImage.java");
    /** Not module sources: VCS, build output, agent scratch, notes and history. */
    private static final Set<String> SKIPPED = Set.of(".git", "target", "agent", ".claude", "docs", "issues", "node_modules");
    private static final Pattern LITERAL = Pattern.compile("postgres:[0-9][^\"'\\s)]*");
    private static final Pattern COMPOSE = Pattern.compile("(docker-)?compose([.-][\\w.-]+)?\\.ya?ml");
    /** The PG 18 image declares this volume and keeps PGDATA below it (/var/lib/postgresql/18/docker); observed on 18.6. */
    private static final String PG18_VOLUME = "/var/lib/postgresql";

    private static List<Path> walk(Predicate<Path> keep) {
        List<Path> out = new ArrayList<>();
        try {
            Files.walkFileTree(ROOT, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                    return !dir.equals(ROOT) && SKIPPED.contains(dir.getFileName().toString())
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                    if (a.isRegularFile() && keep.test(ROOT.relativize(file))) out.add(file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static boolean underSrc(Path relative) {
        for (Path segment : relative) if (segment.toString().equals("src")) return true;
        return false;
    }

    /** Every source file of every module (any depth, any extension), relative to the repository root. */
    private static List<Path> moduleSources() {
        return walk(PostgresImageSourcesTest::underSrc);
    }

    static Stream<Path> composeFiles() {
        return walk(p -> COMPOSE.matcher(p.getFileName().toString()).matches()).stream().map(ROOT::relativize).sorted();
    }

    @Test
    void noModuleSourceNamesAPostgresImageButTheConstant() throws IOException {
        Map<Path, Set<String>> hits = new TreeMap<>();
        Set<String> modules = new TreeSet<>();
        for (Path file : moduleSources()) {
            Path relative = ROOT.relativize(file);
            modules.add(relative.getName(0).toString());
            // ISO-8859-1 reads any byte sequence: a binary resource cannot abort the scan
            Matcher m = LITERAL.matcher(Files.readString(file, StandardCharsets.ISO_8859_1));
            while (m.find()) hits.computeIfAbsent(relative, k -> new TreeSet<>()).add(m.group());
        }
        // the scan is not vacuous: it walked the modules that start containers, and it sees the constant's own literal
        assertTrue(modules.containsAll(Set.of("flottila-runtime", "flottila-bench", "flottila-cli", "flottila-testkit")),
                "the scan walked every module's src/, saw " + modules);
        assertEquals(Set.of(PostgresImage.NAME), hits.get(CONSTANT),
                "the scan finds the pinned tag in the constant itself, all hits: " + hits);
        Map<Path, Set<String>> elsewhere = new TreeMap<>(hits);
        elsewhere.remove(CONSTANT);
        assertTrue(elsewhere.isEmpty(), elsewhere.size() + " source files name a postgres image instead of PostgresImage: "
                + elsewhere);
    }

    @Test
    void bothComposeStacksAreFound() {
        List<Path> found = composeFiles().toList();
        assertTrue(found.containsAll(List.of(Path.of("docker-compose.yml"), Path.of("deploy", "compose", "docker-compose.yml"))),
                "the quickstart and the gate stack are among the compose files found: " + found);
    }

    private static JsonNode postgresService(Path composeFile) throws IOException {
        JsonNode services = new ObjectMapper(new YAMLFactory()).readTree(ROOT.resolve(composeFile).toFile()).path("services");
        JsonNode postgres = services.get("postgres");
        assertTrue(postgres != null, composeFile + " has a postgres service");
        return postgres;
    }

    @ParameterizedTest
    @MethodSource("composeFiles")
    void everyComposeFileRunsPostgresOnTheConstantsTag(Path composeFile) throws IOException {
        assertEquals(PostgresImage.NAME, postgresService(composeFile).path("image").asText(),
                composeFile + ": the postgres service runs the pinned tag of PostgresImage");
        JsonNode services = new ObjectMapper(new YAMLFactory()).readTree(ROOT.resolve(composeFile).toFile()).path("services");
        for (var it = services.fields(); it.hasNext(); ) {
            var e = it.next();
            String image = e.getValue().path("image").asText("");
            String repository = image.contains(":") ? image.substring(0, image.lastIndexOf(':')) : image;
            if (repository.equals("postgres") || repository.endsWith("/postgres")) {
                assertEquals(PostgresImage.NAME, image, composeFile + ": service " + e.getKey() + " runs another postgres image");
            }
        }
    }

    @ParameterizedTest
    @MethodSource("composeFiles")
    void everyComposeFileMountsThePostgresVolumeWhereThe18ImageKeepsItsData(Path composeFile) throws IOException {
        List<String> targets = new ArrayList<>();
        for (JsonNode volume : postgresService(composeFile).path("volumes")) {
            if (volume.isObject()) {
                targets.add(volume.path("target").asText());
            } else {
                String[] parts = volume.asText().split(":");
                targets.add(parts.length >= 2 ? parts[1] : parts[0]);
            }
        }
        assertTrue(targets.contains(PG18_VOLUME), composeFile + ": the database volume is mounted at " + PG18_VOLUME
                + " (PGDATA is /var/lib/postgresql/18/docker below it), mounts: " + targets);
        // the 18 entrypoint refuses to start when /var/lib/postgresql/data is a mount (observed on 18.6)
        assertFalse(targets.contains(PG18_VOLUME + "/data"), composeFile + ": nothing is mounted at the pre-18 "
                + PG18_VOLUME + "/data, mounts: " + targets);
    }
}
