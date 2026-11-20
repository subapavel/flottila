/**
 * Deploying a package: {@code Deployments} takes a package to running flows — validate, build, start, one deploy at a
 * time per node; a redeploy swaps only the flows whose effective hash changed and answers a {@code DeployResult} —
 * lists what runs, and stops all of it on close; a refusal is a {@code DeployRefusedException} by its kind.
 *
 * @since 0.1
 */
package dev.rail8.runtime.deploy;
