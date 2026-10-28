package io.flottila.runtime.deploy;

/** P1-09c3a: the in-memory record store (dev, tests, and {@code Deployments}' old constructor) passes the store contract. */
class InMemoryDeploymentStoreTest extends DeploymentStoreContract {

    @Override
    protected DeploymentStore emptyStore() {
        return new InMemoryDeploymentStore();
    }
}
