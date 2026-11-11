package io.flottila.runtime.node;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * FIX-146: TLS material made at test time with the JDK's own {@code keytool} (no committed key, no new dependency): a CA and the
 * server certificates it signs, as PEM files a Postgres server and a client read. Keys are EC P-256, valid two days. Nothing here
 * is a secret worth keeping: each run makes fresh material under the caller's directory.
 */
final class TestCertificates {

    private static final String PASS = "fix146-store";

    private final Path dir;

    private TestCertificates(Path dir) { this.dir = dir; }

    /** A fresh CA under {@code dir}/{@code name}. */
    static TestCertificates ca(Path dir, String name) throws IOException, InterruptedException {
        Path home = Files.createDirectories(dir.resolve(name));
        TestCertificates ca = new TestCertificates(home);
        keytool(home, "-genkeypair", "-alias", "ca", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=Flottila FIX-146 " + name,
                "-validity", "2", "-ext", "bc:c", "-storetype", "PKCS12", "-keystore", "ca.p12", "-storepass", PASS);
        keytool(home, "-exportcert", "-alias", "ca", "-rfc", "-keystore", "ca.p12", "-storepass", PASS, "-file", "ca.pem");
        return ca;
    }

    /** The CA's certificate, PEM: what a client is given as {@code FLOTTILA_PG_SSL_ROOT_CERT}. */
    Path certificate() { return dir.resolve("ca.pem"); }

    /** A server certificate this CA signs, its subject {@code CN=<cn>} and its subject alternative names {@code san} (keytool syntax). */
    Server server(String name, String cn, String san) throws Exception {
        String store = name + ".p12";
        keytool(dir, "-genkeypair", "-alias", name, "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=" + cn, "-validity", "2",
                "-storetype", "PKCS12", "-keystore", store, "-storepass", PASS);
        keytool(dir, "-certreq", "-alias", name, "-keystore", store, "-storepass", PASS, "-file", name + ".csr");
        keytool(dir, "-gencert", "-alias", "ca", "-keystore", "ca.p12", "-storepass", PASS, "-infile", name + ".csr", "-outfile", name + ".crt",
                "-rfc", "-validity", "2", "-ext", "san=" + san, "-ext", "eku=serverAuth", "-ext", "ku=digitalSignature");
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(dir.resolve(store))) {
            keys.load(in, PASS.toCharArray());
        }
        Key key = keys.getKey(name, PASS.toCharArray());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(key.getEncoded())
                + "\n-----END PRIVATE KEY-----\n";   // PKCS#8, unencrypted: what the server reads at its start
        Files.writeString(dir.resolve(name + ".key"), pem, StandardCharsets.US_ASCII);
        return new Server(Files.readAllBytes(dir.resolve(name + ".crt")), pem.getBytes(StandardCharsets.US_ASCII));
    }

    /** A server's certificate and its private key, both PEM. */
    record Server(byte[] certificate, byte[] key) { }

    private static void keytool(Path cwd, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(args));
        Path log = cwd.resolve("keytool.log");
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        Process p = builder.start();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("fixture: keytool " + args[0] + " did not end within 60 s");
        }
        if (p.exitValue() != 0) throw new IllegalStateException("fixture: keytool " + String.join(" ", args) + " exited " + p.exitValue() + ": " + Files.readString(log));
    }
}
