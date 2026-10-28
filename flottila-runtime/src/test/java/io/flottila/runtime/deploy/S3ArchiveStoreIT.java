package io.flottila.runtime.deploy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.localstack.LocalStackContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-09c3a (D-070): the S3 archive store against an S3 API in a container (LocalStack 4, as {@code S3TierIT}) passes the archive
 * store contract; the archive of a key is the object {@code <prefix>/archives/<sha256>.zip}; and every SDK failure that is not a
 * missing object — an endpoint nothing listens on, a bucket that does not exist — is an {@link IOException} naming the bucket and
 * the key, never a {@link NoSuchFileException}.
 */
class S3ArchiveStoreIT extends ArchiveStoreContract {

    static final LocalStackContainer localstack = new LocalStackContainer("localstack/localstack:4").withServices("s3");
    static final String BUCKET = "flottila-archives-it";
    static S3Client s3;

    /** The case's prefix: every case stores under its own. */
    String prefix;

    @BeforeAll
    static void start() {
        localstack.start();
        s3 = client(localstack.getEndpoint());
        s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }

    @AfterAll
    static void stop() {
        s3.close();
        localstack.stop();
    }

    /** A client as S3TierIT builds it, to {@code endpoint}. */
    static S3Client client(URI endpoint) {
        return S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .forcePathStyle(true)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    @Override
    protected ArchiveStore emptyStore() {
        prefix = "it-" + System.nanoTime() + "/fleet";
        return new S3ArchiveStore(s3, BUCKET, prefix);
    }

    @Override
    protected String keyOf(String sha256) {
        return prefix + "/archives/" + sha256 + ".zip";
    }

    /** A loopback port nothing listens on: bound by the kernel, then released. */
    static int closedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.ofLiteral("127.0.0.1"))) {
            return s.getLocalPort();
        }
    }

    static boolean causedBySdk(Throwable t) {
        for (Throwable c = t.getCause(); c != null; c = c.getCause()) if (c instanceof SdkException) return true;
        return false;
    }

    /** {@code failure} is an IOException other than NoSuchFileException, names {@code bucket} and {@code key}, and carries the SDK's exception. */
    static void assertNamedIOException(Throwable failure, String bucket, String key, String what) {
        assertFalse(failure instanceof NoSuchFileException, what + ": not a missing object: " + failure);
        String message = String.valueOf(failure.getMessage());
        assertTrue(message.contains(bucket) && message.contains(key), what + ": the IOException names the bucket " + bucket + " and the key " + key + ": " + message);
        assertTrue(causedBySdk(failure), what + ": the SDK's exception is its cause: " + failure);
    }

    @Test
    void theObjectKeyIsPrefixArchivesSha256Zip() throws Exception {
        byte[] zip = bytes(11, 9_999);
        String sha = sha256(zip);
        put(sha, archive("shop.zip", zip));
        put(sha, archive("again.zip", zip));
        List<S3Object> under = s3.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).prefix(prefix + "/").build()).contents();
        Map<String, Long> objects = under.stream().collect(Collectors.toMap(S3Object::key, S3Object::size));
        assertEquals(Map.of(prefix + "/archives/" + sha + ".zip", (long) zip.length), objects,
                "one object under the prefix, <prefix>/archives/<sha256>.zip, holding the archive; no package name in the key");
    }

    @Test
    void anUnreachableEndpointIsAnIOException() throws Exception {
        byte[] zip = bytes(12, 1_000);
        String sha = sha256(zip);
        Path upload = archive("shop.zip", zip);
        String key = "fleet/archives/" + sha + ".zip";
        try (S3Client down = client(URI.create("http://127.0.0.1:" + closedPort()))) {
            ArchiveStore unreachable = new S3ArchiveStore(down, BUCKET, "fleet");
            IOException putFailed = assertThrows(IOException.class, () -> put(unreachable, sha, upload), "a put to an endpoint nothing listens on");
            assertNamedIOException(putFailed, BUCKET, key, "put");
            IOException fetchFailed = assertThrows(IOException.class, () -> fetch(unreachable, sha, target("down.zip")), "a fetch from it");
            assertNamedIOException(fetchFailed, BUCKET, key, "fetch");
        }
    }

    /** A 404 that is not a missing object: an implementation mapping every 404 to NoSuchFileException shows here. */
    @Test
    void aMissingBucketIsAnIOExceptionNotANoSuchFile() throws Exception {
        byte[] zip = bytes(13, 1_000);
        String sha = sha256(zip);
        String absent = "absent-" + System.nanoTime();
        ArchiveStore nowhere = new S3ArchiveStore(s3, absent, "fleet");
        String key = "fleet/archives/" + sha + ".zip";
        IOException putFailed = assertThrows(IOException.class, () -> put(nowhere, sha, archive("shop.zip", zip)), "a put to a bucket that does not exist");
        assertNamedIOException(putFailed, absent, key, "put");
        IOException fetchFailed = assertThrows(IOException.class, () -> fetch(nowhere, sha, target("nowhere.zip")), "a fetch from it");
        assertNamedIOException(fetchFailed, absent, key, "fetch");
    }
}
