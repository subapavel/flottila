package dev.rail8.runtime.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.internal.storage.file.GC;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** The contract of {@link PackageRepositories} on JGit over {@code file://} repositories, and what only the JGit cache does. */
class GitCacheTest extends PackageRepositoriesContract {

    private GitSource git;

    @Override
    protected Source newSource() {
        git = new GitSource(temp.resolve("source"));
        return git;
    }

    @Override
    protected Cache cache(Options options) {
        GitSettings settings = new GitSettings(git.base(), options.path(), options.ref(), java.util.Optional.empty(), GitSettings.TIMEOUT_DEFAULT,
                options.maxBytes());
        GitCache cache = new GitCache(settings, temp.resolve("cache"), options.maxPackageBytes(), options.deployed(), timer, options.named());
        return cache::open;
    }

    @Test
    void keepsACommitItAnsweredThroughAForcePushAndACleanupOfTheCache() throws Exception {
        String first = git.commit("orders", "main", pkg("orders"));
        Cache cache = cache(Options.defaults());
        cache.open().forDeploy("orders", first);

        git.delete("orders");
        String rewritten = git.commit("orders", "main", Map.of("package.yaml", file("name: orders\nrewritten: true\n")));
        cache.open().forDeploy("orders", rewritten);
        try (FileRepository cached = new FileRepository(temp.resolve("cache").resolve("orders.git").toFile())) {
            assertNotNull(cached.exactRef("refs/rail8/kept/" + first));
            GC gc = new GC(cached);
            gc.setExpireAgeMillis(0);
            gc.gc().get();
        }
        git.delete("orders");

        cache.open().recorded("orders", first).write(Files.createDirectories(temp.resolve("out")));
        assertEquals("name: orders\n", Files.readString(temp.resolve("out").resolve("package.yaml")));
    }

    @Test
    void readsTheWorkingFoldersOfClonesUnderAFileBaseWithATemporaryCache() throws Exception {
        Path folder = Files.createDirectories(temp.resolve("workspace").resolve("demo"));
        Files.writeString(folder.resolve("package.yaml"), "name: demo\n");
        Files.createDirectories(folder.resolve("flows"));
        Files.writeString(folder.resolve("flows").resolve("in.yaml"), "flow: in\n");
        PersonIdent who = new PersonIdent("Rail8 test", "test@rail8.invalid", Instant.parse("2026-11-19T08:00:00Z"), ZoneOffset.UTC);
        String head;
        try (Git repo = Git.init().setDirectory(folder.toFile()).setInitialBranch("main").call()) {
            repo.add().addFilepattern(".").call();
            head = repo.commit().setMessage("first\n").setAuthor(who).setCommitter(who).setSign(false).call().name();
        }
        Files.writeString(folder.resolve("flows").resolve("in.yaml"), "flow: changed, not committed\n");

        GitCache cache = new GitCache(new GitSettings(temp.resolve("workspace").toUri()), Files.createTempDirectory(temp, "cli-cache"),
                1L << 20, false, timer, java.util.Set::of);
        Commit commit = cache.open().forDeploy("demo", "HEAD");
        assertEquals(head, commit.id());
        Path out = Files.createDirectories(temp.resolve("out"));
        commit.write(out);
        assertEquals("flow: in\n", Files.readString(out.resolve("flows").resolve("in.yaml")), "the committed bytes, not the working folder's");
    }
}
