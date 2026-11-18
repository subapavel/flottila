package dev.rail8.api;

/**
 * What the node offers a step's own code, beyond its config: its secrets and a cache. A {@link StepFactory} gets it in
 * {@link StepResources#services()} at build time and may hold what it returns. Together with the {@link Envelope} (read it,
 * return a new one through its {@code with} methods) and standard logging this is the API for custom code: the code logs
 * through {@code System.Logger}, or SLF4J in a library, and needs no logging type of the node's own.
 *
 * @since 0.1
 */
public interface Services {

    /** Services of resources built without a node: {@link #secret} refuses every call, and {@link #cache()} keeps nothing. */
    Services NONE = new NoServices();

    /**
     * The secret that the option {@code key} of {@code step} names, its whole value {@code secret://<name>}. The value is read
     * through the returned {@link Secret} when the step needs it, never kept in the flow. A factory calls this inside
     * {@link StepFactory#create}, so a secret that is not set refuses the deploy, naming the step, the key and where the node
     * looks for it. On a node a secret {@code secret://<name>} is the environment variable {@code RAIL8_SECRET_<NAME>}
     * ({@code .} and {@code -} as {@code _}). {@code rail8 validate} and {@code rail8 test} read every secret as
     * {@code https://secret.invalid/}. In a flow whose steps hold secrets, when one of its steps that a factory built fails,
     * every value they were handed is replaced by its reference in the failure the node records, parks, logs and answers with.
     *
     * @throws IllegalArgumentException naming the step and the key when the option is absent, not a string, not a reference
     *                                  (its value is not shown), not a well-formed reference, or names a secret that is not set
     * @throws IllegalStateException    after the flow has been built
     */
    Secret secret(StepConfig step, String key);

    /**
     * The cache of the step. Its keys are shared by every step of the same type in the flows of the same package and
     * met by no other: a key the steps of another package or another type use is another entry. A node keeps it in
     * memory, within a node-wide limit; entries stay across a redeploy of the package.
     */
    Cache cache();
}
