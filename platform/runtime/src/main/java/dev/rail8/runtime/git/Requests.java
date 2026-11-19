package dev.rail8.runtime.git;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The rules for what a caller of {@link PackageRepositories} names: a repository, a commit and, in {@code dev} and {@code ci}, a
 * ref. A refusal is an {@link IllegalArgumentException} that does not repeat the value.
 */
final class Requests {

    /** One path segment of at most 100 letters, digits, {@code .}, {@code _} and {@code -}. */
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9._-]{1,100}");
    private static final Pattern COMMIT = Pattern.compile("[0-9a-fA-F]{40}");
    /** The longest ref a deploy may name. */
    private static final int REF_MAX = 255;

    private Requests() {}

    /** {@code name} when it is a repository's name: one segment under the base, not {@code .} or {@code ..}. */
    static String repository(String name) {
        if (name == null || !REPOSITORY.matcher(name).matches() || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("a repository is named by one segment of at most 100 letters, digits, '.', '_' and '-', not '.' or '..'");
        }
        return name;
    }

    /** Whether {@code value} is a commit's id: 40 hex digits, of either case. */
    static boolean isCommit(String value) {
        return value != null && COMMIT.matcher(value).matches();
    }

    /** {@code id}, lower-cased, when it is a commit's id. */
    static String commit(String id) {
        if (!isCommit(id)) throw new IllegalArgumentException("a commit is named by its id of 40 hex digits");
        return id.toLowerCase(Locale.ROOT);
    }

    /**
     * The full names a ref the deploy names may stand for, in the order they are looked up: {@code HEAD}; a full name under
     * {@code refs/heads/} or {@code refs/tags/}; or a short name, a branch before a tag. Refused in a deployed environment, where a
     * deploy names a commit by its id only.
     */
    static List<String> ref(String ref, boolean deployed) {
        if (deployed) throw new IllegalArgumentException("a deployed environment takes a commit by its id of 40 hex digits only, never a ref");
        if (ref == null || ref.isEmpty() || ref.length() > REF_MAX) throw notARef();
        if (ref.equals(Constants.HEAD)) return List.of(Constants.HEAD);
        if (ref.startsWith(Constants.R_REFS)) {
            if (!(ref.startsWith(Constants.R_HEADS) || ref.startsWith(Constants.R_TAGS)) || !Repository.isValidRefName(ref)) throw notARef();
            return List.of(ref);
        }
        if (!Repository.isValidRefName(Constants.R_HEADS + ref)) throw notARef();
        return List.of(Constants.R_HEADS + ref, Constants.R_TAGS + ref);
    }

    private static IllegalArgumentException notARef() {
        return new IllegalArgumentException("a commit is named by its id of 40 hex digits, or in dev and ci by a ref: HEAD, a branch or tag"
                + " name, or a full name under refs/heads/ or refs/tags/ of at most " + REF_MAX + " characters");
    }
}
