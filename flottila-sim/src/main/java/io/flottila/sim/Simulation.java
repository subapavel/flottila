package io.flottila.sim;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * Runs one seed (D-101): builds {@code new Runtime(FakeClock, SimTimer, FakeIds("sim"), blobs, sha256, components)} and the flows over
 * the {@link World}'s seams, drives {@code ticks} ticks of units on one driver virtual thread, drains with faults off, and
 * judges. Interrupted while it waits — the per-seed hang guard — it prints every run still RUNNING with its stack to
 * {@code System.err}, interrupts the driver and throws an {@link IllegalStateException} naming them; a driver that fails is an
 * {@link IllegalStateException} with its cause.
 */
public final class Simulation {

    private Simulation() {}

    public static Verdict run(SimSeed seed, SimConfig config, int ticks, World world) {
        SimRun sim = new SimRun(seed, config, ticks, world);
        FutureTask<Verdict> drive = new FutureTask<>(sim::drive);
        Thread driver = Thread.ofVirtual().name("sim-driver-" + seed.hex()).start(drive);
        String run = "seed=" + seed.hex() + " config=" + config.name() + " ticks=" + ticks + " world=" + world.name();
        try {
            return drive.get();
        } catch (InterruptedException hang) {
            String waitingOn = "SIM HANG " + run + ": the driver waits on\n" + sim.unclassified();
            System.err.println(waitingOn);
            driver.interrupt();
            Thread.currentThread().interrupt();
            throw new IllegalStateException(waitingOn, hang);
        } catch (ExecutionException failed) {
            throw new IllegalStateException("SIM ERROR " + run + ": " + failed.getCause(), failed.getCause());
        }
    }
}
