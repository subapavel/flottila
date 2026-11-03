package io.flottila.sim;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import static io.flottila.sim.Pins.pinned;
import static io.flottila.sim.Stream.FAULTS;
import static io.flottila.sim.Stream.SCHEDULE;
import static io.flottila.sim.Stream.WORKLOAD;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** D-101 seeds: a stream's seed is SplitMix64(run ^ its explicit salt); a tick's generator is its own. */
class SimSeedTest {

    /** PLACEHOLDER (landing): each stream's explicit salt, e.g. {@code Map.of(WORKLOAD, 0x…L, FAULTS, 0x…L, SCHEDULE, 0x…L)}. */
    private static final Map<Stream, Long> SALTS = Map.of(WORKLOAD, 0x6a09e667f3bcc908L, FAULTS, 0xbb67ae8584caa73bL, SCHEDULE, 0x3c6ef372fe94f82bL);

    /** PLACEHOLDER (landing): {@code new SimSeed(1).of(s)} for each stream, printed once and pinned. */
    private static final Map<Stream, Long> GOLDEN_SEED_1 = Map.of(WORKLOAD, 0x63cfc62a2b097592L, FAULTS, 0xabfab36a73262e6bL, SCHEDULE, 0x06c45d188009454fL);

    @Test
    void streamsComeFromFixedSaltsNotOrder() {
        SimSeed one = new SimSeed(1);
        Map<Stream, Long> values = new EnumMap<>(Stream.class);
        Set<Long> salts = new HashSet<>();
        for (Stream s : Stream.values()) {
            values.put(s, one.of(s));
            salts.add(s.salt());
            assertNotEquals((long) s.ordinal(), s.salt(), s + "'s salt is its declaration order");
            assertNotEquals(1L ^ s.salt(), one.of(s), s + ": the stream seed is mixed, not the bare run ^ salt");
        }
        assertEquals(3, salts.size(), "every stream has a salt of its own: " + salts);
        assertEquals(3, new HashSet<>(values.values()).size(), "every stream draws from a seed of its own: " + values);
        // SplitMix64(run ^ salt): a run carrying another stream's salt in place of this one's lands on this stream's seed
        for (Stream a : Stream.values()) {
            for (Stream b : Stream.values()) {
                assertEquals(one.of(a), new SimSeed(1L ^ a.salt() ^ b.salt()).of(b), "of(" + b + ") of run 1 ^ salt(" + a + ") ^ salt(" + b + ")");
            }
        }
        for (Stream s : Stream.values()) {
            assertEquals(pinned(SALTS.get(s), "Stream." + s + ".salt()"), s.salt(), s + "'s salt moved: every pinned seed moves with it");
            assertEquals(pinned(GOLDEN_SEED_1.get(s), "new SimSeed(1).of(" + s + ")"), one.of(s), s + " of seed 1 moved");
        }
    }

    @Test
    void aTicksDrawsIgnoreEarlierTicks() {
        SimSeed seed = new SimSeed(0x5eedL);
        long[] fresh = draws(seed.tick(WORKLOAD, 7));
        for (long t = 0; t < 7; t++) draws(seed.tick(WORKLOAD, t));   // the ticks before drawn from, many times
        for (long t = 0; t < 7; t++) draws(seed.tick(FAULTS, t));
        assertArrayEquals(fresh, draws(seed.tick(WORKLOAD, 7)), "tick 7 after ticks 0..6 were drawn from");
        assertArrayEquals(fresh, draws(new SimSeed(0x5eedL).tick(WORKLOAD, 7)), "tick 7 of a fresh seed of the same run");

        assertFalse(Arrays.equals(fresh, draws(seed.tick(WORKLOAD, 8))), "tick 8 draws what tick 7 does");
        assertFalse(Arrays.equals(fresh, draws(seed.tick(WORKLOAD, 7 + (1L << 32)))), "tick 7 + 2^32 draws what tick 7 does: the tick is cut to an int");
        assertFalse(Arrays.equals(fresh, draws(seed.tick(FAULTS, 7))), "FAULTS tick 7 draws what WORKLOAD tick 7 does");
        assertFalse(Arrays.equals(fresh, draws(seed.tick(SCHEDULE, 7))), "SCHEDULE tick 7 draws what WORKLOAD tick 7 does");
        assertFalse(Arrays.equals(fresh, draws(new SimSeed(0x5eedL + 1).tick(WORKLOAD, 7))), "run 0x5eef draws what run 0x5eed does");
    }

    @Test
    void theMixerIsSplitMix64() {
        // worked example: the first output of Vigna's SplitMix64 from state 0
        assertEquals(0xE220A8397B1DCDAFL, Mix.splitMix64(0L));
    }

    private static long[] draws(SplittableRandom r) {
        long[] out = new long[4];
        for (int i = 0; i < out.length; i++) out[i] = r.nextLong();
        return out;
    }
}
