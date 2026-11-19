package dev.rail8.api.plugin.violation;

import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;

/** Counter-fixture: a class of the plugin contract that names {@code HttpServerRequest}, which its rule allows. */
public final class PluginNamesAnHttpRequest {
    private PluginNamesAnHttpRequest() {}
    public static Handler<HttpServerRequest> paused() { return HttpServerRequest::pause; }
}
