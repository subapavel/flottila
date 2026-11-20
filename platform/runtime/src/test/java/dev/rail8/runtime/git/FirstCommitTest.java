package dev.rail8.runtime.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FirstCommitTest {

    @TempDir Path dir;

    @Test void theFolderBecomesARepositoryOnMainWithOneCommitOfEveryFile() throws Exception {
        Files.writeString(dir.resolve("a.txt"), "a\n");
        Files.createDirectories(dir.resolve("sub"));
        Files.writeString(dir.resolve("sub/b.txt"), "b\n");

        String id = FirstCommit.write(dir, "first\n");

        try (Git git = Git.open(dir.toFile())) {
            assertEquals("main", git.getRepository().getBranch());
            List<RevCommit> commits = new ArrayList<>();
            git.log().call().forEach(commits::add);
            assertEquals(1, commits.size());
            assertEquals(id, commits.getFirst().name());
            assertEquals("first\n", commits.getFirst().getFullMessage());
            List<String> paths = new ArrayList<>();
            try (TreeWalk walk = new TreeWalk(git.getRepository())) {
                walk.addTree(commits.getFirst().getTree());
                walk.setRecursive(true);
                while (walk.next()) paths.add(walk.getPathString());
            }
            assertEquals(List.of("a.txt", "sub/b.txt"), paths);
            assertTrue(git.status().call().isClean(), "nothing is left uncommitted");
        }
    }
}
