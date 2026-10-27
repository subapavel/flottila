/**
 * Deploying a package (D-059, D-060): {@code PackageArchive} unpacks an uploaded archive into a directory
 * after checking all of it — entry count, names that stay below the target, and the bytes it actually inflates to.
 * {@code Deployments} takes a package from its archive to running flows — unpack, validate, build, start, one deploy at a
 * time per node; a redeploy swaps only the flows whose effective hash changed (D-063) and answers a {@code DeployResult} —
 * lists what runs, and stops all of it on close; a refusal is a {@code DeployRefusedException} by its kind.
 *
 * @since 0.1
 */
package io.flottila.runtime.deploy;
