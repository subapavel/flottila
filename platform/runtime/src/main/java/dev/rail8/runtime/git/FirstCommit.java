package dev.rail8.runtime.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Makes a folder a new git repository on {@code main} whose one commit holds every file in it, as {@code rail8 new-adapter} does
 * for the adapter it writes. The commit's author is the user's git identity, as git reads it; the commit is not signed. Blocking.
 *
 * @since 0.1
 */
public final class FirstCommit {

    private FirstCommit() {}

    /**
     * @param dir the folder, which holds no repository yet
     * @param message the commit's message
     * @return the commit's 40-hex id
     * @throws IOException when the repository cannot be made or the commit fails
     */
    public static String write(Path dir, String message) throws IOException {
        Objects.requireNonNull(dir, "dir");
        Objects.requireNonNull(message, "message");
        try (Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
            git.add().addFilepattern(".").call();
            return git.commit().setMessage(message).setSign(false).call().name();
        } catch (GitAPIException failed) {
            throw new IOException("the first commit of " + dir + " failed: " + failed.getMessage(), failed);
        }
    }
}
