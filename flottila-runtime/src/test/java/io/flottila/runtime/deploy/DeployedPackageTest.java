package io.flottila.runtime.deploy;

import io.flottila.schemas.Package;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A listed package is a value: what the node lists does not change when the lists it was built from do (P2-02a, D-078).
 *
 * <p>P2-02b (D-087) shape: the record gains {@code String content} ({@code sha256:<hex>}) and {@code List<Dependency> dependencies},
 * {@code Dependency(String name, String version, String content, String archiveSha256, boolean direct)}, after {@code problems}.
 */
class DeployedPackageTest {

    /** Its folders and artifacts are copies taken at construction, and cannot be changed through the record. */
    @Test
    void theFoldersAndArtifactsAreCopiesOfTheListsGiven() {
        Package.Folder urnA = new Package.Folder("urn-a", Optional.of("urn:example:a"), Optional.empty());
        Package.Artifact orders = new Package.Artifact("urn-a/orders.yaml", "flow", Optional.of("orders"));
        List<Package.Folder> folders = new ArrayList<>(List.of(urnA));
        List<Package.Artifact> artifacts = new ArrayList<>(List.of(orders));

        DeployedPackage p = new DeployedPackage("shop", "1.0.0", Optional.empty(), "ab".repeat(32), List.of("shop/orders"), Instant.EPOCH,
                List.of(), folders, artifacts, DeployedPackage.State.RUNNING, List.of(), "sha256:" + "cd".repeat(32), List.of());
        folders.add(new Package.Folder("urn-b", Optional.empty(), Optional.empty()));
        artifacts.clear();

        assertEquals(List.of(urnA), p.folders(), "the folders as given, not as changed after");
        assertEquals(List.of(orders), p.artifacts(), "the artifacts as given, not as changed after");
        assertThrows(UnsupportedOperationException.class, () -> p.folders().clear());
        assertThrows(UnsupportedOperationException.class, () -> p.artifacts().clear());
    }

    /** Its dependencies are a copy taken at construction, and cannot be changed through the record. */
    @Test
    void theDependenciesAreACopyOfTheListGiven() {
        DeployedPackage.Dependency common = new DeployedPackage.Dependency("common", "1.2.0", "sha256:" + "ef".repeat(32), "01".repeat(32), true);
        List<DeployedPackage.Dependency> deps = new ArrayList<>(List.of(common));

        DeployedPackage p = new DeployedPackage("invoice", "1.0.0", Optional.empty(), "ab".repeat(32), List.of(), Instant.EPOCH, List.of(), List.of(),
                List.of(), DeployedPackage.State.RUNNING, List.of(), "sha256:" + "cd".repeat(32), deps);
        deps.clear();

        assertEquals(List.of(common), p.dependencies(), "the dependencies as given, not as changed after");
        assertEquals("sha256:" + "cd".repeat(32), p.content());
        assertThrows(UnsupportedOperationException.class, () -> p.dependencies().clear());
    }
}
