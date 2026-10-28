package io.flottila.runtime.deploy;

import io.flottila.schemas.Package;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A listed package is a value: what the node lists does not change when the lists it was built from do (P2-02a, D-078). */
class DeployedPackageTest {

    /** Its folders and artifacts are copies taken at construction, and cannot be changed through the record. */
    @Test
    void theFoldersAndArtifactsAreCopiesOfTheListsGiven() {
        Package.Folder urnA = new Package.Folder("urn-a", Optional.of("urn:example:a"), Optional.empty());
        Package.Artifact orders = new Package.Artifact("urn-a/orders.yaml", "flow", Optional.of("orders"));
        List<Package.Folder> folders = new ArrayList<>(List.of(urnA));
        List<Package.Artifact> artifacts = new ArrayList<>(List.of(orders));

        DeployedPackage p = new DeployedPackage("shop", "1.0.0", Optional.empty(), "ab".repeat(32), List.of("shop/orders"), Instant.EPOCH,
                List.of(), folders, artifacts, DeployedPackage.State.RUNNING, List.of());
        folders.add(new Package.Folder("urn-b", Optional.empty(), Optional.empty()));
        artifacts.clear();

        assertEquals(List.of(urnA), p.folders(), "the folders as given, not as changed after");
        assertEquals(List.of(orders), p.artifacts(), "the artifacts as given, not as changed after");
        assertThrows(UnsupportedOperationException.class, () -> p.folders().clear());
        assertThrows(UnsupportedOperationException.class, () -> p.artifacts().clear());
    }
}
