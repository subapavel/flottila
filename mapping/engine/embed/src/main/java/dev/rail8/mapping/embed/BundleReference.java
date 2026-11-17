package dev.rail8.mapping.embed;

import java.util.Objects;

/**
 * A reference a schema bundle lists, in one of its two spellings: a path relative to the mapping
 * file, or a package reference {@code name@version:/path}.
 */
public sealed interface BundleReference {

  /** The reference as the mapping file writes it. */
  String spelling();

  /**
   * A path relative to the mapping file, such as {@code ../structures/a.xsd}.
   *
   * @param path the path as the mapping file writes it
   */
  record Relative(String path) implements BundleReference {

    public Relative {
      Objects.requireNonNull(path, "path");
    }

    @Override
    public String spelling() {
      return path;
    }
  }

  /**
   * A package reference, such as {@code common@1.4.0:/a.xsd}: the file {@code path} of the package
   * {@code name} at {@code version}.
   *
   * @param spelling the reference as the mapping file writes it
   * @param name the package's name, {@code common}
   * @param version the package's version, {@code 1.4.0}
   * @param path the file inside the package, without its leading {@code /}: {@code a.xsd}
   */
  record Packaged(String spelling, String name, String version, String path)
      implements BundleReference {

    public Packaged {
      Objects.requireNonNull(spelling, "spelling");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(version, "version");
      Objects.requireNonNull(path, "path");
    }
  }
}
