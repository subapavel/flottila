package io.flottila.api.violation;

import io.vertx.core.AsyncResult;
import io.vertx.core.Completable;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.Pipe;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.StreamBase;
import io.vertx.core.streams.WriteStream;

/** Counter-fixture: every entry of the D-020 allowlist, and nothing else from Vert.x. Must pass the surface rules. */
public final class ApiUsesAllowedVertxTypes {
    private ApiUsesAllowedVertxTypes() {}

    public static Future<Buffer> drain(ReadStream<Buffer> in, WriteStream<Buffer> out) {
        byte[] primitiveArray = new byte[0];                 // arrays of primitives are JDK too
        long[] longs = {primitiveArray.length};
        Promise<Buffer> promise = Promise.promise();
        if (longs[0] != 0) return Future.failedFuture("unreachable");
        Handler<AsyncResult<CompositeFuture>> done = ar -> promise.complete(Buffer.buffer());
        Completable<Void> completable = (v, err) -> { };
        Pipe<Buffer> pipe = in.pipe();
        StreamBase base = out;
        CompositeFuture all = Future.all(pipe.to(out), promise.future());
        all.onComplete(done);
        base.exceptionHandler(promise::tryFail);
        completable.succeed(null);
        return promise.future();
    }
}
