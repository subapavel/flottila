package dev.rail8.api.plugin;

/**
 * A service of the admin API that a module brings: one route, {@code /admin/modules/<plugin name>/<path>}, behind the admin
 * API's browser guard and its token. The node builds a module's services once, at its start
 * ({@link Plugin#services(PluginSettings)}), and runs each call of the route on a virtual thread of its own, where
 * {@link #handle} may block. One instance answers concurrent calls, so it must be safe for that, and it keeps no state between
 * calls. Only a module of the node's class path brings services; a plugin of {@code RAIL8_PLUGINS} brings none.
 *
 * <p>What the node does around {@link #handle}:
 * <ul>
 * <li>Before it, and then {@code handle} is not called: a body over {@link #MAX_REQUEST_BYTES} is 413, a POST whose
 *     {@code Content-Type} is not {@code application/json} is 415, and a body that is not UTF-8 is 400.</li>
 * <li>At most 4 calls of one plugin run at once; a fifth is 503 at once. A call holds its place until {@code handle} returns.
 *     The thread of {@code handle} is interrupted when the client's connection closes, and when the node stops.</li>
 * <li>After it: a valid reply ({@link AdminReply}) goes to the client as it is. Anything else is a 500, logged with the plugin
 *     and the path: an invalid reply, or an exception.</li>
 * </ul>
 * The node's own answers among these are problems (RFC 9457) sent as {@code application/problem+json}, each of type
 * {@code urn:rail8:admin:problem:<slug>}: {@code too-large} (413), {@code unsupported-media-type} (415), {@code not-utf-8}
 * (400), {@code busy} (503) and {@code invalid-reply} (500).
 *
 * @since 0.3
 */
public interface AdminService {

    /** The largest body of a request, in bytes. */
    long MAX_REQUEST_BYTES = 16L << 20;

    /** The largest reply, in bytes of its JSON in UTF-8. */
    long MAX_REPLY_BYTES = 16L << 20;

    /** The route's method: {@code GET} or {@code POST}. */
    String method();

    /**
     * The route's path under the module: 1 to 4 segments of {@code [a-z0-9-]{1,32}}, joined by single slashes, with no slash at
     * the start or the end, such as {@code catalogue} or {@code mappings/compile}.
     */
    String path();

    /**
     * Answers one call.
     *
     * @param request the call's body and the node's clock
     * @return the reply, sent to the client when it is valid
     * @throws InterruptedException the call was interrupted: its client went away, or the node stops
     */
    AdminReply handle(AdminRequest request) throws InterruptedException;
}
