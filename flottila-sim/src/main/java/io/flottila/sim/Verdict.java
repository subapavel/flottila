package io.flottila.sim;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The outcome of one run (D-101).
 *
 * @param seed the run's seed
 * @param config its configuration
 * @param ticks how many ticks it ran
 * @param world the {@link World#name()} it ran
 * @param failures what the oracle found, in the order it found it
 * @param compared per check, how many comparisons it made
 * @param traceDigest sha256 over the run's events sorted by (tick, message id, seq), maps key-sorted
 */
public record Verdict(SimSeed seed, SimConfig config, int ticks, String world, List<Failure> failures, Map<Check, Long> compared,
                      String traceDigest) {

    public Verdict {
        failures = List.copyOf(failures);
        compared = Map.copyOf(compared);
    }

    /**
     * One thing the oracle found.
     *
     * @param check the check that failed
     * @param tick the tick it failed at
     * @param detail what it saw
     */
    public record Failure(Check check, long tick, String detail) {}

    /** No failure and Σ{@link #compared} &gt; 0; a run that compared nothing fails {@link Check#EMPTY}. */
    public boolean passed() {
        return failures.isEmpty() && total() > 0;
    }

    /** The checks that failed, {@link Check#EMPTY} included. */
    public Set<Check> failedChecks() {
        Set<Check> failed = EnumSet.noneOf(Check.class);
        for (Failure f : failures) failed.add(f.check());
        if (total() == 0) failed.add(Check.EMPTY);
        return failed;
    }

    /**
     * One {@code SIM FAIL seed=0x… config=… ticks=… world=… check=… tick=…: <detail>} line per failure, then the command that
     * replays the run; a run that passed has one {@code SIM PASS …} line with its comparisons instead.
     */
    public String report() {
        String run = "seed=" + seed.hex() + " config=" + config.name() + " ticks=" + ticks + " world=" + world;
        StringBuilder out = new StringBuilder();
        if (passed()) out.append("SIM PASS ").append(run).append(" compared=").append(new TreeMap<>(compared)).append('\n');
        for (Failure f : failures) {
            out.append("SIM FAIL ").append(run).append(" check=").append(f.check()).append(" tick=").append(f.tick()).append(": ")
                    .append(f.detail()).append('\n');
        }
        if (total() == 0) out.append("SIM FAIL ").append(run).append(" check=").append(Check.EMPTY).append(" tick=").append(ticks)
                .append(": no check compared anything\n");
        return out.append("mvn -B -ntp -q -pl flottila-sim -am test -Dtest=SimReplayTest -Dsurefire.failIfNoSpecifiedTests=false")
                .append(" -Dsim.seed=").append(seed.hex()).append(" -Dsim.config=").append(config.name()).append(" -Dsim.ticks=").append(ticks)
                .append(" -Dsim.world=").append(world).toString();
    }

    private long total() {
        long sum = 0;
        for (long n : compared.values()) sum += n;
        return sum;
    }
}
