package io.flottila.runtime;

import io.vertx.mutiny.core.Vertx;

import java.util.Map;
import java.util.concurrent.ExecutorService;

/** Used only at build time to inject dependencies into stages. One Runtime per tenant. */
public record Runtime(Vertx vertx, Map<String, Component> components, MessageStore messages,
                      MonitoringStore monitoring, ExecutorService cpuPool, ExecutorService blockingPool, Xml xml,
                      WorkQueue workQueue, DlqStore dlqStore) {

    public Component component(String scheme) {
        Component c = components.get(scheme);
        if (c == null) throw new IllegalArgumentException("no component for scheme " + scheme);
        return c;
    }
}
