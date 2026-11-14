package io.flottila.runtime.deploy;

import io.flottila.runtime.node.OnVirtualThread;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-09c3a (D-070), beside the contract {@code S3ArchiveStoreIT} runs: a put of a file that is not there is the file system's
 * {@link NoSuchFileException} naming that file, as for the dir store — raised before any request, so the client here points at a
 * port nothing listens on, where a request would end in an IOException of another kind.
 */
class S3ArchiveStoreTest {

    @TempDir Path tmp;

    static int closedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.ofLiteral("127.0.0.1"))) {
            return s.getLocalPort();
        }
    }

    @Test
    void aPutOfAFileThatIsNotThereIsNoSuchFileNamingIt() throws Exception {
        try (S3Client nowhere = S3Client.builder().region(Region.EU_CENTRAL_1).endpointOverride(URI.create("http://127.0.0.1:" + closedPort()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIAEXAMPLE", "secret-example")))
                .forcePathStyle(true).httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            ArchiveStore store = new S3ArchiveStore(nowhere, "flottila-archives", "fleet", 64L << 20);
            Path absent = tmp.resolve("absent.zip");
            NoSuchFileException missing = assertThrows(NoSuchFileException.class, () -> OnVirtualThread.call(() -> {
                store.put("ab".repeat(32), absent);
                return null;
            }));
            assertEquals(absent.toString(), missing.getFile(), "the file the put was given");
        }
    }

    @Test
    void aCapBelowOneIsRefusedAtConstructionAndOneIsAccepted() throws Exception {
        try (S3Client nowhere = S3Client.builder().region(Region.EU_CENTRAL_1).endpointOverride(URI.create("http://127.0.0.1:" + closedPort()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIAEXAMPLE", "secret-example")))
                .forcePathStyle(true).httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            for (long bad : new long[] {0L, -1L}) {
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                        () -> new S3ArchiveStore(nowhere, "flottila-archives", "fleet", bad), "cap " + bad);
                assertTrue(String.valueOf(e.getMessage()).contains(String.valueOf(bad)), "the refusal names " + bad + ": " + e.getMessage());
            }
            assertNotNull(new S3ArchiveStore(nowhere, "flottila-archives", "fleet", 1L), "a cap of 1 is the smallest legal");
        }
    }
}
