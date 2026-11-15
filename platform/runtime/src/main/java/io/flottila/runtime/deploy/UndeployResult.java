package io.flottila.runtime.deploy;

import java.util.List;
import java.util.Objects;

/**
 * What {@link Deployments#undeploy} did (D-095): the package undeployed, the version it was listed with, and the flows it no
 * longer lists, by qualified id ({@code <package>/<flow>}, as {@link Deployments#flows()} lists them), sorted; none for a package
 * listed {@link DeployedPackage.State#FAILED}.
 *
 * @param name the package undeployed
 * @param version the version it was listed with
 * @param flowsRemoved its flows, by qualified id, sorted
 * @since 0.1
 */
public record UndeployResult(String name, String version, List<String> flowsRemoved) {
    public UndeployResult {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        flowsRemoved = flowsRemoved.stream().sorted().toList();
    }
}
