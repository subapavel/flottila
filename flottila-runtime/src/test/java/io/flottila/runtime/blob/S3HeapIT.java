package io.flottila.runtime.blob;

import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.Hash;
import io.flottila.api.Hasher;
import io.flottila.testkit.BlobStoreContract;
import io.flottila.testkit.GeneratedReadStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.localstack.LocalStackContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs in its own JVM with {@code -Xmx96m} (runtime pom, execution {@code heap-bounded-it}): a 50 MiB body
 * goes up as multipart and comes back; one 5 MiB part (plus the SDK's copy of it) is the ceiling, a store
 * that holds the body dies with OutOfMemoryError.
 */
class S3HeapIT {

    static final LocalStackContainer localstack = new LocalStackContainer("localstack/localstack:4").withServices("s3");
    static S3Client s3;
    static final String BUCKET = "flottila-heap-it";
    static final Hasher HASHER = Hasher.sha256();

    @BeforeAll
    static void start() {
        localstack.start();
        s3 = S3Client.builder().endpointOverride(localstack.getEndpoint()).region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .forcePathStyle(true).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }

    @AfterAll
    static void stop() {
        s3.close();
        localstack.stop();
    }

    @Test
    void fiftyMegabytesGoUpAsMultipartInsideANinetySixMegabyteHeap(@TempDir Path spool) {
        assertTrue(Runtime.getRuntime().maxMemory() < 128L << 20, "this test only means something under the small heap: max " + (Runtime.getRuntime().maxMemory() >> 20) + " MiB");
        TieredBlobStore s = new TieredBlobStore(new TieredBlobStore.Config(4096, 0, 1L << 20, spool, TieredBlobStoreTest.key(),
                new TieredBlobStore.S3Target(s3, BUCKET, "heap"), true), HASHER);
        long size = 50L * 1024 * 1024;
        Body body = BlobStoreContract.await(s.write(BlobKey.body("big", 0), new GeneratedReadStream(size, 65536), size));
        Hash readBack = BlobStoreContract.await(new TieredBlobStoreTest.Reader().hashOf(s.open(body.key())));
        assertEquals(size, body.size());
        assertEquals(GeneratedReadStream.expectedHash(HASHER, size), body.hash());
        assertEquals(body.hash(), readBack);
        assertEquals(Optional.of(TieredBlobStore.Location.S3), s.location(body.key()));
    }
}
