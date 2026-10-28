package io.flottila.runtime.deploy;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * The {@link ArchiveStore} of a deployed node (D-070): the archive of a key is the object {@code <prefix>/archives/<sha256>.zip}
 * in {@code bucket} — no package name in the key. A put is a plain PUT of the file every time (no HEAD first); a fetch is a GET
 * copied to the target, as {@code TieredBlobStore} reads its S3 tier. A missing object is a {@link NoSuchFileException} naming
 * the key; every other SDK failure — an endpoint nothing listens on, a bucket that does not exist — is an {@link IOException}
 * naming the bucket and the key, the SDK's exception its cause. The client is the node's: this store never closes it.
 *
 * @since 0.1
 */
public final class S3ArchiveStore implements ArchiveStore {

    private final S3Client client;
    private final String bucket;
    private final String prefix;

    public S3ArchiveStore(S3Client client, String bucket, String prefix) {
        this.client = Objects.requireNonNull(client, "client");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
        this.prefix = Objects.requireNonNull(prefix, "prefix");
    }

    @Override
    public void put(String sha256, Path archive) throws IOException {
        String key = key(sha256);
        RequestBody body;
        try {
            body = RequestBody.fromFile(archive);   // reads the file's size now: one that is not there fails here
        } catch (UncheckedIOException unreadable) {
            throw unreadable.getCause();
        }
        try {
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), body);
        } catch (SdkException failed) {
            throw failed("put", key, failed);
        }
    }

    @Override
    public void fetch(String sha256, Path target) throws IOException {
        String key = key(sha256);
        try (InputStream in = client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (NoSuchKeyException missing) {
            NoSuchFileException absent = new NoSuchFileException(key, null, "no archive under this key in the bucket " + bucket);
            absent.initCause(missing);
            throw absent;
        } catch (SdkException failed) {
            throw failed("fetch", key, failed);
        }
    }

    private String key(String sha256) {
        return prefix + "/archives/" + ArchiveName.of(sha256);
    }

    private IOException failed(String what, String key, SdkException failure) {
        return new IOException("the " + what + " of the archive s3://" + bucket + "/" + key + " failed: " + failure.getMessage(), failure);
    }
}
