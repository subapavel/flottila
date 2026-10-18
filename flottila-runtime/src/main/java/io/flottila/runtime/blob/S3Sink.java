package io.flottila.runtime.blob;

import io.vertx.core.buffer.Buffer;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * Direct streaming upload to S3 (ARCHITECTURE §4: "direct multipart to S3"; spool-then-upload was
 * rejected). Bytes are buffered to one part ({@value #PART_BYTES}) and uploaded as they fill; a body that
 * never fills one part is a single PutObject on finish. Memory held: one part, never the body.
 */
final class S3Sink implements Sink {

    static final int PART_BYTES = 5 * 1024 * 1024;   // S3's minimum part size except for the last part

    private final TieredBlobStore.S3Target target;
    private final String key;
    private Buffer part = Buffer.buffer();
    private String uploadId;
    private final List<CompletedPart> parts = new ArrayList<>();

    S3Sink(TieredBlobStore.S3Target target, String key) {
        this.target = target;
        this.key = key;
    }

    @Override public void write(Buffer chunk) {
        part.appendBuffer(chunk);
        while (part.length() >= PART_BYTES) {
            Buffer full = part.slice(0, PART_BYTES).copy();
            Buffer rest = part.slice(PART_BYTES, part.length()).copy();
            uploadPart(full);
            part = rest;
        }
    }

    private void uploadPart(Buffer bytes) {
        if (uploadId == null) {
            uploadId = target.client().createMultipartUpload(CreateMultipartUploadRequest.builder().bucket(target.bucket()).key(key).build()).uploadId();
        }
        int number = parts.size() + 1;
        String etag = target.client().uploadPart(UploadPartRequest.builder().bucket(target.bucket()).key(key).uploadId(uploadId).partNumber(number).build(),
                RequestBody.fromBytes(bytes.getBytes())).eTag();
        parts.add(CompletedPart.builder().partNumber(number).eTag(etag).build());
    }

    @Override public void finish() {
        if (uploadId == null) {
            target.client().putObject(PutObjectRequest.builder().bucket(target.bucket()).key(key).build(), RequestBody.fromBytes(part.getBytes()));
            return;
        }
        if (part.length() > 0) uploadPart(part);
        target.client().completeMultipartUpload(CompleteMultipartUploadRequest.builder().bucket(target.bucket()).key(key).uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build()).build());
    }

    @Override public void abort() {
        if (uploadId != null) {
            try {
                target.client().abortMultipartUpload(AbortMultipartUploadRequest.builder().bucket(target.bucket()).key(key).uploadId(uploadId).build());
            } catch (RuntimeException ignored) {
                // best effort: an orphaned multipart upload is cleaned by the bucket's lifecycle rule, never by the hot path
            }
        }
    }
}
