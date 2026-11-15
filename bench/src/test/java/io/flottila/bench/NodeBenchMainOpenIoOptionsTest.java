package io.flottila.bench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * CORE-15b: {@code --io-device <name>} names the block device holding Postgres' data directory, as {@code /proc/diskstats}
 * lists it; the open loop's {@code io} series reads it ({@link DiskSamplerTest}). Unset, no device is read.
 */
class NodeBenchMainOpenIoOptionsTest {

    @Test
    void anIoDeviceIsTakenAsNamedAndIsAbsentUnlessNamed() {
        NodeBenchMain.Open named = NodeBenchMain.options(new String[] {"--scenario", "ack", "--rate", "2000", "--io-device", "nvme0n1"}).open();
        assertEquals("nvme0n1", named.ioDevice(), "the device as /proc/diskstats names it");
        assertEquals("nvme0n1", NodeBenchMain.options(new String[] {"--scenario", "echo", "--rate", "10", "--io-device", "nvme0n1"}).open()
                .ioDevice(), "any open scenario takes it");

        assertNull(NodeBenchMain.options(new String[] {"--scenario", "ack", "--rate", "2000"}).open().ioDevice(), "no device unless named");
    }
}
