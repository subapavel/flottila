package dev.rail8.runtime.admin;

import dev.rail8.api.plugin.AdminReply;
import dev.rail8.api.plugin.AdminRequest;
import dev.rail8.api.plugin.AdminService;
import io.vertx.core.Context;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Semaphore;

/**
 * The routes of the admin services the node's modules bring, {@code <method> /admin/modules/<plugin>/<path>}, one for each
 * service; mounted behind the browser guard and the token.
 *
 * <p>On the event loop, before a call, each answered at once: a POST whose {@code Content-Type} is not {@code application/json}
 * is 415, a declared {@code Content-Length} above {@link AdminService#MAX_REQUEST_BYTES} is 413, and a call of a plugin that
 * already runs {@link #CALLS} calls is 503. Then a POST's body is read into memory, 413 at byte max + 1; a GET's body is not
 * read. On the call's own virtual thread: a body that is not UTF-8 is 400, otherwise {@link AdminService#handle} runs and its
 * reply is checked. The call holds its place from before its body is read until {@code handle} has returned and its reply is
 * checked; the place is free before the answer is written. A valid reply goes to the client as it is; anything else is a 500,
 * logged with the plugin and the route.
 *
 * <p>The thread of a running call is interrupted when its client's connection closes, and so when the server stops: closing the
 * server closes every connection. A client that goes away while its body is read frees the call's place. An answer given before the request's body was read to
 * its end closes the connection once written: the rest is never read. The node's own answers are problems (RFC 9457) of type
 * {@code urn:rail8:admin:problem:<slug>}.
 */
final class ModuleCalls {

    /** How many calls of one plugin run at once. */
    static final int CALLS = 4;

    private static final System.Logger LOG = System.getLogger(ModuleCalls.class.getName());
    private static final String JSON = "application/json";
    private static final String PROBLEM = "application/problem+json";

    private final AdminServer.Modules modules;
    private final Context context;

    /** The calls of {@code modules}' services, their answers written on {@code context}: the listener's. */
    ModuleCalls(AdminServer.Modules modules, Context context) {
        this.modules = Objects.requireNonNull(modules, "modules");
        this.context = Objects.requireNonNull(context, "context");
    }

    /** Adds a route for each service to {@code router}; the calls of each plugin share {@link #CALLS} places of their own. */
    void mount(Router router) {
        modules.services().forEach((plugin, services) -> {
            Semaphore places = new Semaphore(CALLS);
            for (AdminService service : services) {
                String method = service.method();
                String route = "/admin/modules/" + plugin + "/" + service.path();
                boolean post = method.equals("POST");
                router.route(HttpMethod.valueOf(method), route).handler(ctx -> call(ctx, plugin + " " + method + " " + route, post, service, places));
            }
        });
    }

    /** On the event loop: the refusals answered at once, else the call takes a place of its plugin's {@code places}. */
    private void call(RoutingContext ctx, String what, boolean post, AdminService service, Semaphore places) {
        HttpServerRequest req = ctx.request();
        // Netty refused a Content-Length that is not a non-negative number before routing
        String declared = req.getHeader(HttpHeaders.CONTENT_LENGTH);
        if (post && !json(req.getHeader(HttpHeaders.CONTENT_TYPE))) {
            answer(ctx, problem(415, "unsupported-media-type", "Unsupported media type", "the body of a POST to an admin service is " + JSON));
        } else if (declared != null && Long.parseLong(declared) > AdminService.MAX_REQUEST_BYTES) {
            answer(ctx, tooLarge());
        } else if (!places.tryAcquire()) {
            answer(ctx, problem(503, "busy", "Busy", CALLS + " calls of this plugin run already; try again later"));
        } else {
            new Call(ctx, what, service, places).begin(post);
        }
    }

    /** Whether a {@code Content-Type} names {@code application/json}, in any case, with or without parameters. */
    private static boolean json(String contentType) {
        return contentType != null && contentType.split(";", 2)[0].strip().equalsIgnoreCase(JSON);
    }

    /** One call from the moment it holds a place: on the event loop until its thread starts, then on that thread. */
    private final class Call {
        private final RoutingContext ctx;
        private final String what;
        private final AdminService service;
        private final Semaphore places;
        private final Buffer body = Buffer.buffer();
        private boolean reading;   // on the event loop: the body is being read, and the place is this call's to free
        private Thread thread;     // on the event loop: the call's thread, once it starts

        Call(RoutingContext ctx, String what, AdminService service, Semaphore places) {
            this.ctx = ctx;
            this.what = what;
            this.service = service;
            this.places = places;
        }

        /** Watches the connection, then reads a POST's body and starts the call at its end, or starts a GET's at once. */
        void begin(boolean post) {
            ctx.addEndHandler(end -> {   // fails when the connection closes before the answer is out
                if (end.failed()) gone();
            });
            if (!post) {
                start();
                return;
            }
            reading = true;
            HttpServerRequest req = ctx.request();
            req.exceptionHandler(failed -> gone());
            req.handler(chunk -> {
                if (!reading) return;
                if (body.length() + (long) chunk.length() > AdminService.MAX_REQUEST_BYTES) {
                    req.pause();
                    giveUp();
                    answer(ctx, tooLarge());
                    return;
                }
                body.appendBuffer(chunk);
            });
            req.endHandler(end -> {
                if (!reading) return;
                reading = false;
                start();
            });
            req.resume();
        }

        /** The client went away: a call still reading its body frees its place, a running one is interrupted. */
        private void gone() {
            if (reading) giveUp();
            else if (thread != null) thread.interrupt();
        }

        private void giveUp() {
            reading = false;
            places.release();
        }

        private void start() {
            thread = Thread.ofVirtual().name("rail8-admin").unstarted(() -> {
                Answer answer = run();
                context.runOnContext(v -> answer(ctx, answer));
            });
            thread.start();
        }

        /** On the call's thread: the answer to the body read; the place is free once it returns. */
        private Answer run() {
            try {
                String json;
                try {
                    json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body.getBytes())).toString();
                } catch (CharacterCodingException notUtf8) {
                    return problem(400, "not-utf-8", "Not UTF-8", "the body is not UTF-8");
                }
                return valid(service.handle(new AdminRequest(json, modules.clock())));
            } catch (InterruptedException interrupted) {   // its client went away, or the server stops: no one reads the answer
                LOG.log(System.Logger.Level.INFO, "admin service " + what + ": interrupted");
                return invalidReply();
            } catch (Throwable failed) {   // the service threw: a 500, never a hang
                return invalid("failed", failed);
            } finally {
                places.release();
            }
        }

        /** The reply as the client gets it when it is valid ({@link AdminReply}), else the 500. */
        private Answer valid(AdminReply reply) {
            if (reply == null) return invalid("no reply", null);
            int status = reply.status();
            if (status != 200 && status != 201 && (status < 400 || status > 599)) return invalid("status " + status, null);
            String json = reply.json();
            if (json.length() > AdminService.MAX_REPLY_BYTES) return invalid("a reply above " + AdminService.MAX_REPLY_BYTES + " bytes", null);   // never fewer bytes than chars
            ByteBuffer bytes;
            try {
                bytes = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(json));
            } catch (CharacterCodingException notUtf8) {
                return invalid("a reply that is not UTF-8", null);
            }
            if (bytes.remaining() > AdminService.MAX_REPLY_BYTES) return invalid("a reply above " + AdminService.MAX_REPLY_BYTES + " bytes", null);
            Object value;
            try {
                value = Json.decodeValue(json);
            } catch (DecodeException notJson) {
                return invalid("a reply that is not JSON: " + notJson.getMessage(), null);
            }
            if (status >= 400 && !(value instanceof JsonObject problem && problem.getValue("status") instanceof Integer s && s == status)) {
                return invalid("a " + status + " whose JSON is not a problem of status " + status, null);
            }
            byte[] out = new byte[bytes.remaining()];
            bytes.get(out);
            return new Answer(status, status < 400 ? JSON : PROBLEM, Buffer.buffer(out));
        }

        private Answer invalid(String why, Throwable failure) {
            LOG.log(System.Logger.Level.WARNING, "admin service " + what + ": " + why, failure);
            return invalidReply();
        }
    }

    // --- answers (on the context)

    /** A status, a content type and the body. */
    private record Answer(int status, String type, Buffer body) {}

    private static Answer invalidReply() {
        return problem(500, "invalid-reply", "Invalid reply", "the admin service did not answer as it must; the node's log says why");
    }

    private static Answer tooLarge() {
        return problem(413, "too-large", "Request too large", "the body is above " + AdminService.MAX_REQUEST_BYTES + " bytes");
    }

    private static Answer problem(int status, String slug, String title, String detail) {
        return new Answer(status, PROBLEM, new JsonObject().put("type", "urn:rail8:admin:problem:" + slug).put("title", title)
                .put("status", status).put("detail", detail).toBuffer());
    }

    /**
     * Writes {@code answer} unless the client is gone; once it is written, a request whose body was not read to its end has its
     * connection closed.
     */
    private static void answer(RoutingContext ctx, Answer answer) {
        HttpServerResponse res = ctx.response();
        if (res.closed()) return;
        HttpServerRequest req = ctx.request();
        res.setStatusCode(answer.status()).putHeader(HttpHeaders.CONTENT_TYPE, answer.type())
                .putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(answer.body().length())).end(answer.body())
                .onComplete(written -> {
                    if (!req.isEnded()) req.connection().close();
                });
    }
}
