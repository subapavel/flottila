package io.flottila.runtime.deploy;

import java.util.List;
import java.util.Objects;

/**
 * What {@link Deployments#deploy} did (D-063): the package as deployed now and, by qualified flow id, sorted, the flows it
 * started for the first time ({@code added}), those it stopped and started again on the new version ({@code swapped}) and those it
 * stopped because the new version no longer has them ({@code removed}). A flow in none of the three runs on as it was: its
 * effective hash did not change. A first deploy adds every flow.
 *
 * @param pkg the package deployed
 * @param added flows new to the package
 * @param swapped flows whose effective hash changed
 * @param removed flows the new version no longer has
 * @since 0.1
 */
public record DeployResult(DeployedPackage pkg, List<String> added, List<String> swapped, List<String> removed) {
    public DeployResult {
        Objects.requireNonNull(pkg, "pkg");
        added = added.stream().sorted().toList();
        swapped = swapped.stream().sorted().toList();
        removed = removed.stream().sorted().toList();
    }
}
