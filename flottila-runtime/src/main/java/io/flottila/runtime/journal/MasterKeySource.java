package io.flottila.runtime.journal;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Where the journal's master key comes from (D-106): the key every tenant key is wrapped under ({@link JournalKeys}). One
 * of {@value #FILE} (preferred: a file holding the base64 of the key, a trailing newline allowed, as
 * {@code openssl rand -base64 32 > file} writes it) or {@value #ENV} (the base64 itself), exactly 256 bits; one key per
 * environment, shared by all its nodes. There is no default: neither refuses the start in every environment, unless
 * {@value #GENERATE}{@code =true} opts in to a key generated once into {@code <journal>/MASTER} — beside the data it
 * protects, for one dev node with one copy only — which every start announces with a WARNING. Vault/KMS come later behind
 * this seam. A variable set to the empty string counts as not set (as {@code Secrets} reads them). No text of this class —
 * a refusal, a log line, its {@code toString} — carries a key value.
 *
 * @since 0.1
 */
public final class MasterKeySource {

    static final String FILE = "FLOTTILA_JOURNAL_KEY_FILE";
    static final String ENV = "FLOTTILA_JOURNAL_KEY";
    static final String GENERATE = "FLOTTILA_JOURNAL_KEY_GENERATE";
    /** The generated key's file in the journal directory. */
    static final String MASTER = "MASTER";

    private static final System.Logger LOG = System.getLogger(MasterKeySource.class.getName());
    private static final int KEY_BYTES = 32;

    /** {@value #FILE}, or null. */
    private final String file;
    /** {@value #ENV}, or null: never shown. */
    private final String value;
    private final boolean generate;

    private MasterKeySource(String file, String value, boolean generate) {
        this.file = file;
        this.value = value;
        this.generate = generate;
    }

    /**
     * The source {@code env} names: its three keys and nothing else of it. Refused at once (an
     * {@link IllegalArgumentException} naming the keys): both {@value #FILE} and {@value #ENV}; {@value #GENERATE} other than
     * {@code true} or {@code false}; {@value #GENERATE}{@code =true} beside a given key. Neither is refused by {@link #load},
     * not here: a configuration that starts no node (a dry build) needs no key.
     */
    public static MasterKeySource fromEnv(Map<String, String> env) {
        String file = given(env, FILE);
        String value = given(env, ENV);
        String generate = given(env, GENERATE);
        if (generate != null && !generate.equals("true") && !generate.equals("false")) {
            throw new IllegalArgumentException(GENERATE + " must be true or false");
        }
        if (file != null && value != null) {
            throw new IllegalArgumentException(FILE + " and " + ENV + " are both set: the journal's master key comes from one (" + FILE + " preferred)");
        }
        boolean generated = "true".equals(generate);
        if (generated && (file != null || value != null)) {
            throw new IllegalArgumentException(GENERATE + "=true beside " + (file != null ? FILE : ENV)
                    + ": the journal's master key is given or generated, never both");
        }
        return new MasterKeySource(file, value, generated);
    }

    private static String given(Map<String, String> env, String key) {
        String v = env.get(key);
        return v == null || v.isEmpty() ? null : v;
    }

    /**
     * The master key, a 32-byte AES key: decoded from the given file or value, or — opted in — read from
     * {@code <journalDir>/MASTER}, which the first load creates (a temporary file of mode 0600, forced, published as
     * {@code MASTER} by a hard link that never replaces one, {@code journalDir} forced) through {@code files}; every load of a
     * generated key logs one WARNING.
     *
     * @throws IllegalArgumentException no key is given and none is opted in to; a key that is no base64 of exactly 256 bits
     *                                  (a damaged {@code MASTER} is refused, never replaced); a key file that cannot be read
     * @throws IOException              {@code MASTER} could not be created
     */
    public SecretKey load(Path journalDir, JournalFiles files) throws IOException {
        if (value != null) return decode(value, ENV);
        if (file != null) {
            String text;
            try {
                text = Files.readString(Path.of(file), StandardCharsets.ISO_8859_1);
            } catch (IOException | RuntimeException unreadable) {
                throw new IllegalArgumentException(FILE + " names a file that cannot be read: " + file, unreadable);
            }
            return decode(text, FILE + " (" + file + ")");
        }
        if (!generate) {
            throw new IllegalArgumentException("the journal has no master key: set " + FILE + " (preferred) or " + ENV
                    + " to the base64 of 256 bits (openssl rand -base64 32), or " + GENERATE + "=true on a single dev node (D-106); there is no default");
        }
        Path master = journalDir.resolve(MASTER);
        SecretKey key = Files.exists(master) ? decode(Files.readString(master, StandardCharsets.ISO_8859_1), "the generated key file " + master)
                : generated(journalDir, master, files);
        LOG.log(System.Logger.Level.WARNING, "{0}=true: the journal''s master key is the generated file {1}, beside the data it protects (anyone "
                + "who reads the journal directory reads every record). For a single dev node only (D-106): give every other node {2}", GENERATE, master, FILE);
        return key;
    }

    /**
     * The key of {@code master} once a fresh one was published there: written into a temporary file of this load's own
     * (0600 from its creation), forced, then published without replacing — a hard link named {@code MASTER} — so of two
     * first starts at once one key wins and both answer it: a load whose link finds {@code MASTER} there takes that one. The
     * tmp is unlinked, the directory forced, and the key read back from {@code MASTER}. A failure before the link is made
     * unlinks the tmp (an unlink that fails rides on it as suppressed); a failed directory force leaves {@code MASTER},
     * which the next start reads. A tmp a crashed start left stays (another start's may be in use): it is no key of the journal.
     */
    private static SecretKey generated(Path dir, Path master, JournalFiles files) throws IOException {
        byte[] material = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(material);
        byte[] text = (Base64.getEncoder().encodeToString(material) + "\n").getBytes(StandardCharsets.ISO_8859_1);
        try {
            Files.createDirectories(dir);   // a first start: the journal opens its directory after its key
            Path tmp = Files.createTempFile(dir, MASTER + ".", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try {
                try (FileChannel ch = files.open(tmp, Set.of(StandardOpenOption.WRITE))) {
                    JournalIo.writeFully(ch, ByteBuffer.wrap(text));
                    ch.force(false);
                }
                try {
                    Files.createLink(master, tmp);
                } catch (FileAlreadyExistsException published) {
                    // another start published its key first: that one is the journal's
                }
                Files.delete(tmp);
            } catch (IOException | RuntimeException | Error e) {
                JournalIo.unlinkAfter(tmp, e);
                throw e;
            }
            JournalIo.forceDir(files, dir);
            return decode(Files.readString(master, StandardCharsets.ISO_8859_1), "the generated key file " + master);
        } finally {
            Arrays.fill(material, (byte) 0);
            Arrays.fill(text, (byte) 0);
        }
    }

    /** The 256-bit key {@code text} is the base64 of (surrounding whitespace dropped); a refusal names {@code what}, never the text. */
    private static SecretKey decode(String text, String what) {
        byte[] b;
        try {
            b = Base64.getDecoder().decode(text.strip());
        } catch (IllegalArgumentException notBase64) {
            // not the JDK's message as a cause: it quotes the offending character of the key
            throw new IllegalArgumentException(what + " is no base64 (the value is not shown)");
        }
        try {
            if (b.length != KEY_BYTES) {
                throw new IllegalArgumentException(what + " must be the base64 of exactly 256 bits, was " + b.length + " bytes (the value is not shown)");
            }
            return new SecretKeySpec(b, "AES");
        } finally {
            Arrays.fill(b, (byte) 0);
        }
    }

    /** Equal when both name the same file, the same value, or both generate. */
    @Override
    public boolean equals(Object other) {
        return other instanceof MasterKeySource o && Objects.equals(file, o.file) && Objects.equals(value, o.value) && generate == o.generate;
    }

    @Override
    public int hashCode() {
        return Objects.hash(file, value, generate);
    }

    /** The source, never a key value. */
    @Override
    public String toString() {
        if (value != null) return "MasterKeySource[" + ENV + " (not shown)]";
        if (file != null) return "MasterKeySource[" + FILE + "=" + file + "]";
        return generate ? "MasterKeySource[" + GENERATE + "=true]" : "MasterKeySource[none]";
    }
}
