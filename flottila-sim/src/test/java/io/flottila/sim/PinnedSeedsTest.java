package io.flottila.sim;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every line of {@code pinned-seeds.tsv} — {@code seed⇥config⇥ticks⇥world⇥PASS|FAIL:Check,…⇥# origin} (D-101) — runs again
 * and ends as it was pinned: PASS passes, FAIL fails exactly the checks it lists.
 */
class PinnedSeedsTest {

    record Pin(int line, SimSeed seed, String config, int ticks, String world, Set<Check> fails, String origin) {}

    @Test
    void everyLineReproducesItsVerdict() throws IOException {
        List<Pin> pins = pins();
        List<Executable> replays = new ArrayList<>();
        for (Pin p : pins) {
            replays.add(() -> {
                Verdict v = SimRuns.run(p.seed(), SimConfig.named(p.config()), p.ticks(), Worlds.named(p.world()));
                String what = "line " + p.line() + " (" + p.origin() + ")";
                if (p.fails().isEmpty()) assertTrue(v.passed(), () -> what + " was pinned PASS\n" + v.report());
                else assertEquals(p.fails(), v.failedChecks(), () -> what + " was pinned FAIL:" + p.fails() + "\n" + v.report());
            });
        }
        assertAll("pinned seeds", replays);
        Set<String> pinnedWorlds = new TreeSet<>();
        for (Pin p : pins) pinnedWorlds.add(p.world());
        assertTrue(pinnedWorlds.containsAll(Worlds.NAMES), () -> "PLACEHOLDER pinned-seeds.tsv pins " + pinnedWorlds + ", not every world of "
                + Worlds.NAMES + ": the implementer pins a correct-world seed and each variant's detecting seed at landing (P2-10a)");
    }

    private static List<Pin> pins() throws IOException {
        List<Pin> pins = new ArrayList<>();
        try (InputStream in = PinnedSeedsTest.class.getResourceAsStream("pinned-seeds.tsv")) {
            assertNotNull(in, "io/flottila/sim/pinned-seeds.tsv is not on the test classpath");
            List<String> lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\t", -1);
                int n = i + 1;
                assertEquals(6, f.length, () -> "line " + n + " is not seed⇥config⇥ticks⇥world⇥outcome⇥# origin: " + line);
                assertTrue(f[5].startsWith("# "), () -> "line " + n + " names no origin: " + line);
                pins.add(new Pin(n, SimSeed.parse(f[0]), f[1], Integer.parseInt(f[2]), f[3], outcome(f[4], n), f[5].substring(2)));
            }
        }
        return pins;
    }

    /** PASS: no check; FAIL:A,B: those checks, at least one. */
    private static Set<Check> outcome(String text, int line) {
        if (text.equals("PASS")) return Set.of();
        assertTrue(text.startsWith("FAIL:") && text.length() > 5, () -> "line " + line + ": outcome is PASS or FAIL:Check,…, was " + text);
        Set<Check> checks = EnumSet.noneOf(Check.class);
        for (String c : text.substring(5).split(",")) checks.add(Check.valueOf(c));
        return checks;
    }
}
