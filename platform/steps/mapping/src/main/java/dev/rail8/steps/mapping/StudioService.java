package dev.rail8.steps.mapping;

import dev.rail8.api.plugin.AdminReply;
import dev.rail8.api.plugin.AdminRequest;
import dev.rail8.api.plugin.AdminService;
import dev.rail8.mapping.studio.Studio;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * One operation of the mapping studio as a service of the admin API: {@code GET catalogue}, or a {@code POST} of any other
 * operation, at the path of its name. The reply is the studio's answer to the call's body, at the node's clock and its zone.
 *
 * <p>The engine's work runs on a platform thread of its own, which it holds for as long as it computes; the call's virtual
 * thread only awaits that thread. An interrupt of the call is passed on to it, which stops a test run, and the call returns, so
 * frees its place, only once the thread has ended.
 */
final class StudioService implements AdminService {

    private final String operation;

    /** The service of {@code operation}, one of {@link Studio#OPERATIONS}. */
    StudioService(String operation) {
        this.operation = operation;
    }

    @Override public String method() { return operation.equals("catalogue") ? "GET" : "POST"; }

    @Override public String path() { return operation; }

    /**
     * The studio's answer, computed on a platform thread of its own.
     *
     * @throws InterruptedException the call was interrupted; the engine's thread has ended
     * @throws IllegalStateException the studio failed; the node answers 500
     */
    @Override
    public AdminReply handle(AdminRequest request) throws InterruptedException {
        FutureTask<Studio.Reply> answer = new FutureTask<>(() -> Studio.answer(operation, request.json(), request.clock()));
        Thread engine = Thread.ofPlatform().name("rail8 mapping " + operation).daemon(true).start(answer);
        try {
            engine.join();
        } catch (InterruptedException interrupted) {
            engine.interrupt();
            joinUninterruptibly(engine);
            throw interrupted;
        }
        Studio.Reply reply;
        try {
            reply = answer.get();   // done: its thread has ended
        } catch (ExecutionException failed) {
            throw new IllegalStateException("the mapping studio failed on " + operation, failed.getCause());
        }
        return new AdminReply(reply.status(), reply.json());
    }

    /** Waits for {@code thread} to end; an interrupt meanwhile is the call's own, which is thrown already. */
    private static void joinUninterruptibly(Thread thread) {
        while (true) {
            try {
                thread.join();
                return;
            } catch (InterruptedException again) {
                // keep waiting: the call returns only once the engine's thread has ended
            }
        }
    }
}
