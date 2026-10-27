package io.flottila.runtime.blob;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobStore;
import io.flottila.api.Body;
import io.flottila.testkit.BlobStoreContract;
import io.flottila.testkit.GeneratedReadStream;
import io.vertx.core.buffer.Buffer;
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
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The S3 tier against an S3 API in a container (LocalStack, Apache-2.0): the same contract as every
 * other tier, direct multipart for bodies above one part, keyed by message id + step.
 */
class S3TierIT extends BlobStoreContract {

    static final LocalStackContainer localstack = new LocalStackContainer("localstack/localstack:4").withServices("s3");
    static S3Client s3;
    static final String BUCKET = "flottila-it";
    @TempDir Path spool;

    @BeforeAll
    static void start() {
        localstack.start();
        s3 = S3Client.builder()
                .endpointOverride(localstack.getEndpoint())
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .forcePathStyle(true)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
        s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }

    @AfterAll
    static void stop() {
        s3.close();
        localstack.stop();
    }

    TieredBlobStore tiered() {
        return new TieredBlobStore(new TieredBlobStore.Config(TREE_LIMIT, 0, 1L << 20, spool, TieredBlobStoreTest.key(),
                new TieredBlobStore.S3Target(s3, BUCKET, "it/" + System.nanoTime()), true), hasher());
    }

    @Override protected BlobStore store() { return tiered(); }

    /** Above the 5 MiB part size: the body goes up as a multipart upload. */
    @Override protected long bigSize() { return 12L * 1024 * 1024; }

    @Test
    void bodiesLandInS3UnderMessageIdAndStepAndLeaveWhenEvicted() {
        TieredBlobStore s = tiered();
        Body body = await(s.write(BlobKey.body("m-s3", 2), Buffer.buffer("to the bucket"), TREE_LIMIT));
        assertEquals(Optional.of(TieredBlobStore.Location.S3), s.location(body.key()));
        String objectKey = s.objectKey(body.key());
        assertTrue(objectKey.endsWith("/m-s3/2/body"), objectKey);
        assertEquals(13L, s3.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(objectKey).build().toBuilder().build()).contentLength());
        await(s.evict(body.key()));
        assertThrows(NoSuchKeyException.class, () -> s3.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(objectKey).build()));
        assertTrue(awaitOutcome(s.read(body.key())).failed());
    }

    /** D-061: an S3 body is durable already — persist completes at once, even on a store with no Postgres tier, and the body stays in S3. */
    @Test
    void persistOfAnS3BodyCompletesWithoutADurableTier() {
        TieredBlobStore s = tiered();   // the two-argument store: no durable tier
        BlobKey key = BlobKey.body("m-durable", 0);
        Buffer bytes = Buffer.buffer("already outlives the process");
        await(s.write(key, bytes, TREE_LIMIT));
        assertEquals(Optional.of(TieredBlobStore.Location.S3), s.location(key), "fixture: the body went to S3");
        await(s.persist(key));
        await(s.persist(key));
        assertEquals(Optional.of(TieredBlobStore.Location.S3), s.location(key), "nothing moved: the S3 object is the durable copy");
        assertEquals((long) bytes.length(), s3.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(s.objectKey(key)).build()).contentLength(), "the object is still there");
        assertEquals(bytes, await(s.read(key)));
    }

    @Test
    void anAbortedMultipartLeavesNoObject() {
        TieredBlobStore s = tiered();
        BlobKey key = BlobKey.body("abort", 0);
        assertTrue(awaitOutcome(s.write(key, new GeneratedReadStream(9L << 20, 65536), 7L << 20)).failed(), "over the flow's max size after the first part");
        assertEquals(Optional.empty(), s.location(key));
        assertThrows(NoSuchKeyException.class, () -> s3.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(s.objectKey(key)).build()));
    }
}
