package io.flottila.runtime.journal;

import io.flottila.runtime.journal.JournalFixture.Outcome;
import io.flottila.runtime.journal.ReadChannelsTest.GatedFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.flottila.runtime.journal.JournalFixture.onVirtual;
import static io.flottila.runtime.journal.JournalFixture.until;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A swept segment's channel dropped (CORE-4d; the implementer's): an idle one is closed at once, one in use by its last read;
 * a later read of that segment opens a fresh one; a segment with no channel kept is nothing to drop.
 */
class ReadChannelsDropTest {

    @TempDir Path tmp;

    private Path file(String name, String text) throws IOException {
        return Files.writeString(tmp.resolve(name), text, StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test
    void anIdleChannelIsClosedAtOnceAndALaterReadOpensAFreshOne() throws Exception {
        Path a = file("1.seg", "aaaa-segment-one"), b = file("2.seg", "bbbb-segment-two");
        GatedFiles files = new GatedFiles();
        try (ReadChannels channels = new ReadChannels(files, 4)) {
            assertArrayEquals(bytes("aaaa"), channels.pread(1, a, 0, 4), "fixture: segment 1's channel kept");
            assertArrayEquals(bytes("bbbb"), channels.pread(2, b, 0, 4), "fixture: segment 2's channel kept");

            channels.drop(1);
            assertEquals(1, files.closesOf(a), "the idle channel is closed at once");
            assertEquals(0, files.closesOf(b), "another segment's channel stays");
            channels.drop(3);   // no channel kept: nothing to close
            assertEquals(0, files.closesOf(b), "a segment with no channel drops nothing");

            assertArrayEquals(bytes("segment"), channels.pread(1, a, 5, 7), "a later read of the segment reads");
            channels.drop(1);
            assertEquals(2, files.closesOf(a), "on a fresh channel, which the next drop closes");
        }
    }

    @Test
    void aChannelInUseIsClosedByItsLastRead() throws Exception {
        Path a = file("1.seg", "aaaa-segment-one");
        GatedFiles files = new GatedFiles();
        files.gated = a;
        try (ReadChannels channels = new ReadChannels(files, 4)) {
            Outcome<byte[]> reading = onVirtual(() -> channels.pread(1, a, 0, 4));
            until(() -> files.arrived.get() == 1, () -> "the read of segment 1 waits in its pread");

            channels.drop(1);
            assertEquals(0, files.closesOf(a), "not closed under the read in flight");

            files.gate.release();
            assertArrayEquals(bytes("aaaa"), reading.join(), "the read in flight reads");
            assertEquals(1, files.closesOf(a), "and its end closes the dropped channel");
        }
    }
}
