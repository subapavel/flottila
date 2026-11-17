package dev.rail8.steps.mapping.violation;

/** Allowed: the mapping step uses the engine's embedding API. */
public final class MappingStepUsesTheEmbeddingApi {
    private MappingStepUsesTheEmbeddingApi() {}
    public static Class<?> embedding() { return dev.rail8.mapping.embed.Mapping.class; }
}
