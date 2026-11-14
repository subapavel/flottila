package io.flottila.runtime.journal;

import io.flottila.runtime.journal.peer.PeerHandshake;
import io.flottila.runtime.journal.peer.PeerRefusedException;
import io.flottila.runtime.journal.peer.ScriptedPeer;
import io.flottila.runtime.journal.peer.ScriptedPeer.Hello;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static io.flottila.runtime.journal.PeerLinkTest.A;
import static io.flottila.runtime.journal.PeerLinkTest.B;
import static io.flottila.runtime.journal.PeerLinkTest.nonces;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-5a2 O2 (implementer, beside {@link PeerHandshakeVersionTest}): a listener refuses a dialer's HELLO on a channel no
 * version names (0, 4..255), the refusal naming its number; a dialer, which dials channel read, refuses a listener's HELLO on
 * any other channel — repl, bulk or unknown — naming it. The other side is a {@link ScriptedPeer} HELLO; the witness of each
 * case is the same HELLO on channel read, taken by a fresh handshake of the same side.
 */
class PeerHelloChannelTest {

    static Hello dialerHello() {
        return Hello.dialer(B, PeerHandshakeVersionTest.nonce(2));
    }

    static Hello listenerHello() {
        return Hello.listener(A, PeerHandshakeVersionTest.nonce(1));
    }

    @ParameterizedTest(name = "channel {0}")
    @ValueSource(ints = {0, 4, 255})
    void aListenerRefusesADialerOnAnUnknownChannelNamingItsNumber(int channel) throws Exception {
        PeerRefusedException e = assertThrows(PeerRefusedException.class,
                () -> PeerHandshake.listener(A, nonces(1)).proof(dialerHello().channel(channel).frame()), "channel " + channel);
        assertTrue(String.valueOf(e.getMessage()).contains("channel " + channel + " (unknown)"), "names channel " + channel + ": " + e.getMessage());
        assertNotNull(PeerHandshake.listener(A, nonces(1)).proof(dialerHello().frame()), "witness: the same HELLO on channel read");
    }

    @ParameterizedTest(name = "channel {0}: {1}")
    @CsvSource({"2, repl", "3, bulk", "0, 0 (unknown)", "4, 4 (unknown)", "255, 255 (unknown)"})
    void aDialerRefusesAListenerAnsweringOnAnotherChannelThanTheOneItDialled(int channel, String name) throws Exception {
        PeerRefusedException e = assertThrows(PeerRefusedException.class,
                () -> PeerHandshake.dialer(B, "node-a", nonces(2)).proof(listenerHello().channel(channel).frame()), "channel " + channel);
        assertTrue(String.valueOf(e.getMessage()).contains("answered on channel " + name), "names channel " + name + ": " + e.getMessage());
        assertNotNull(PeerHandshake.dialer(B, "node-a", nonces(2)).proof(listenerHello().frame()), "witness: the same HELLO on channel read");
    }
}
