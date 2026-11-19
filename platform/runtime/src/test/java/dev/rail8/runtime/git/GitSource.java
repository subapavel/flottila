package dev.rail8.runtime.git;

import dev.rail8.runtime.git.PackageRepositoriesContract.Entry;
import dev.rail8.runtime.git.PackageRepositoriesContract.File;
import dev.rail8.runtime.git.PackageRepositoriesContract.Link;
import dev.rail8.runtime.git.PackageRepositoriesContract.Submodule;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TagBuilder;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.FileUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TreeMap;

/**
 * Bare git repositories in a folder, built with JGit for tests: the source a {@code file://} base names. Each commit's tree is
 * written entry by entry, so a test can commit what a checkout would refuse, such as {@code .git} in a folder.
 */
final class GitSource implements PackageRepositoriesContract.Source {

    private final Path dir;
    private long commits;

    GitSource(Path dir) {
        this.dir = dir;
    }

    /** The base the repositories live under. */
    URI base() {
        return dir.toUri();
    }

    Path dir(String repository) {
        return dir.resolve(repository);
    }

    private Repository open(String repository) throws IOException {
        Path at = dir(repository);
        if (!Files.isDirectory(at)) {
            try {
                Git.init().setBare(true).setInitialBranch("main").setDirectory(at.toFile()).call().close();
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
        return new FileRepositoryBuilder().setGitDir(at.toFile()).setBare().setMustExist(true).build();
    }

    private PersonIdent who() {
        return new PersonIdent("Rail8 test", "test@rail8.invalid", Instant.parse("2026-11-19T08:00:00Z").plusSeconds(commits), ZoneOffset.UTC);
    }

    @Override
    public String commit(String repository, String branch, Map<String, Entry> tree) {
        try (Repository repo = open(repository); ObjectInserter inserter = repo.newObjectInserter()) {
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(tree(inserter, tree, ""));
            Ref tip = repo.exactRef(Constants.R_HEADS + branch);
            if (tip != null) commit.setParentId(tip.getObjectId());
            commits++;
            commit.setAuthor(who());
            commit.setCommitter(who());
            commit.setMessage("commit " + commits + "\n");
            ObjectId id = inserter.insert(commit);
            inserter.flush();
            move(repo, Constants.R_HEADS + branch, id);
            return id.name();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The tree of the entries under {@code prefix}, written child by child in git's order. */
    private static ObjectId tree(ObjectInserter inserter, Map<String, Entry> entries, String prefix) throws IOException {
        Map<String, Object> children = new TreeMap<>((a, b) -> gitOrder(a, entries, prefix).compareTo(gitOrder(b, entries, prefix)));
        for (Map.Entry<String, Entry> entry : entries.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            String rest = entry.getKey().substring(prefix.length());
            int slash = rest.indexOf('/');
            if (slash < 0) children.put(rest, entry.getValue());
            else children.put(rest.substring(0, slash), prefix + rest.substring(0, slash + 1));
        }
        TreeFormatter formatter = new TreeFormatter();
        for (Map.Entry<String, Object> child : children.entrySet()) {
            switch (child.getValue()) {
                case String folder -> formatter.append(child.getKey(), FileMode.TREE, tree(inserter, entries, folder));
                case File file -> formatter.append(child.getKey(), FileMode.REGULAR_FILE, inserter.insert(Constants.OBJ_BLOB, file.bytes()));
                case Link link -> formatter.append(child.getKey(), FileMode.SYMLINK,
                        inserter.insert(Constants.OBJ_BLOB, link.target().getBytes(StandardCharsets.UTF_8)));
                case Submodule submodule -> formatter.append(child.getKey(), FileMode.GITLINK, ObjectId.fromString(submodule.commit()));
                default -> throw new IllegalStateException(String.valueOf(child.getValue()));
            }
        }
        return inserter.insert(formatter);
    }

    /** A child's name as git sorts it: a folder's with a {@code /} after it. */
    private static String gitOrder(String name, Map<String, Entry> entries, String prefix) {
        return entries.containsKey(prefix + name) ? name : name + "/";
    }

    private static void move(Repository repo, String name, ObjectId id) throws IOException {
        RefUpdate update = repo.updateRef(name);
        update.setNewObjectId(id);
        RefUpdate.Result result = update.forceUpdate();
        if (result != RefUpdate.Result.NEW && result != RefUpdate.Result.FORCED && result != RefUpdate.Result.NO_CHANGE) {
            throw new IOException("fixture: " + name + " not moved: " + result);
        }
    }

    @Override
    public void branch(String repository, String branch, String commit) {
        try (Repository repo = open(repository)) {
            move(repo, Constants.R_HEADS + branch, ObjectId.fromString(commit));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void tag(String repository, String tag, String commit) {
        try (Repository repo = open(repository); ObjectInserter inserter = repo.newObjectInserter()) {
            TagBuilder builder = new TagBuilder();
            builder.setObjectId(ObjectId.fromString(commit), Constants.OBJ_COMMIT);
            builder.setTag(tag);
            builder.setTagger(who());
            builder.setMessage("tag " + tag + "\n");
            ObjectId id = inserter.insert(builder);
            inserter.flush();
            move(repo, Constants.R_TAGS + tag, id);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(String repository) {
        try {
            FileUtils.delete(dir(repository).toFile(), FileUtils.RECURSIVE | FileUtils.SKIP_MISSING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void shallowCopy(String from, String to) {
        try {
            Git.cloneRepository().setURI(dir(from).toUri().toString()).setDirectory(dir(to).toFile()).setBare(true).setDepth(1).call().close();
        } catch (Exception e) {
            throw new IllegalStateException("fixture: shallow copy of " + from, e);
        }
    }
}
