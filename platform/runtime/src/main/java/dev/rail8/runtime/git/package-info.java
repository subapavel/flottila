/**
 * Reading package repositories: {@code PackageRepositories} answers a commit of a repository under the pool's base, and
 * {@code Commit} writes the package's folder of that commit into a directory after checking all of its tree. {@code GitCache} is
 * its implementation on JGit, over a bare repository per package repository in the node's cache; {@code GitSettings} holds the
 * {@code RAIL8_GIT_*} settings, and a {@code GitFailure} names the repository, the commit or ref and one reason of a fixed list.
 * {@code FirstCommit} makes a folder a new repository with one commit, for the adapter {@code rail8 new-adapter} writes.
 * JGit is used in this package only.
 *
 * @since 0.1
 */
package dev.rail8.runtime.git;
