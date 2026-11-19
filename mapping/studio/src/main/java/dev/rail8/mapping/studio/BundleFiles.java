package dev.rail8.mapping.studio;

import dev.rail8.mapping.structure.importer.ReferenceResolver;
import dev.rail8.mapping.structure.importer.ResolvedFile;
import dev.rail8.mapping.structure.importer.UnresolvedReferenceException;
import dev.rail8.mapping.structure.importer.UnresolvedReferenceException.Reason;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The reference resolver of one call, over the bundle files the call sent. It decides each
 * reference on its own, in this order:
 *
 * <ol>
 *   <li>a package reference is refused as {@code PACKAGE_REFERENCE_UNSUPPORTED} from its text;
 *   <li>a reference the call names as escaping is refused with an {@link
 *       InvalidWorkspacePathException};
 *   <li>a reference the call sent a file for is that file;
 *   <li>any other is {@code UNREADABLE}. When the call sent no files at all, the reference is noted
 *       as {@linkplain #needed() needed} too.
 * </ol>
 */
final class BundleFiles implements ReferenceResolver {

  /** A package reference, {@code name@version:/path}. */
  private static final Pattern PACKAGE =
      Pattern.compile(
          "[A-Za-z0-9_-][A-Za-z0-9._-]*@[A-Za-z0-9][A-Za-z0-9.+-]*:/[A-Za-z0-9_-][A-Za-z0-9._-]*"
              + "(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*");

  /** The files the call sent, by reference; null when it sent none. */
  private final Map<String, byte[]> files;

  private final Set<String> escaping;

  /** The references read while the call sent no files, once each, in the order read. */
  private final SequencedSet<String> needed = new LinkedHashSet<>();

  /**
   * @param files the files the call sent, by reference, or null when it sent none
   * @param escaping the references the call names as no workspace path
   */
  BundleFiles(Map<String, byte[]> files, Set<String> escaping) {
    this.files = files;
    this.escaping = escaping;
  }

  @Override
  public ResolvedFile resolve(String reference) throws UnresolvedReferenceException {
    if (PACKAGE.matcher(reference).matches()) {
      throw new UnresolvedReferenceException(Reason.PACKAGE_REFERENCE_UNSUPPORTED, reference);
    }
    if (escaping.contains(reference)) {
      throw new InvalidWorkspacePathException(reference);
    }
    if (files == null) {
      needed.add(reference);
      throw new UnresolvedReferenceException(Reason.UNREADABLE, reference);
    }
    byte[] content = files.get(reference);
    if (content == null) {
      throw new UnresolvedReferenceException(Reason.UNREADABLE, reference);
    }
    return new ResolvedFile(reference, content);
  }

  /** The references read while the call sent no files, once each, in the order read. */
  List<String> needed() {
    return new ArrayList<>(needed);
  }
}
