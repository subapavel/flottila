package io.github.subapavel.jqj;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The exported surface of jq-core (ADR-0016, ADR-0024): one line per module directive, per public type
 * of an exported package and per public or protected member, against the hand-written
 * {@code api/exported-signatures.txt}. Reflection only, so it compiles before the slice exists. The
 * module is found through the class loader, not the working directory: PIT runs it from the oracle
 * module's directory.
 */
class ExportedApiTest {
  private static final String MODULE = "io.github.subapavel.jqj";
  private static final String EXPECTED = "/api/exported-signatures.txt";
  private static final String SEPARATOR = " | ";

  @Test
  void exportedSignaturesAreExactlyTheListedSlice() throws Exception {
    SortedSet<String> expected = expectedLines();
    SortedSet<String> actual = Surface.of(locateModule()).lines();

    List<String> diff = new ArrayList<>();
    for (String line : expected) {
      if (!actual.contains(line)) {
        diff.add("missing: " + line);
      }
    }
    for (String line : actual) {
      if (!expected.contains(line)) {
        diff.add("extra:   " + line);
      }
    }
    assertThat(expected).as("hand-written lines in %s", EXPECTED).isNotEmpty();
    assertThat(diff).as("exported signatures of %s against %s", MODULE, EXPECTED).isEmpty();
  }

  @Test
  void noExportedSignatureNamesQueryCoreOrInternalTypes() throws Exception {
    Surface surface = Surface.of(locateModule());
    Set<String> hidden = surface.unexportedPackages();
    Set<String> referenced = surface.referencedTypes().stream().map(Class::getName).collect(Collectors.toSet());

    // Witnesses: the walk sees parameter, return and type-argument types, and the views' package exists
    // behind the exports, so an empty result below is not an empty walk.
    assertThat(referenced)
        .as("types named by the exported signatures")
        .contains(
            "io.github.subapavel.jqj.value.JqValue",
            "io.github.subapavel.jqj.JqTermination$Cause",
            "java.time.Duration",
            "java.lang.foreign.MemorySegment",
            "java.nio.channels.WritableByteChannel",
            "java.util.function.BiConsumer");
    assertThat(hidden).as("packages of %s that are not exported", MODULE).contains("io.github.subapavel.jqj.internal.value");

    List<String> offenders =
        surface.referencedTypes().stream()
            .filter(c -> hidden.contains(c.getPackageName()) || isQueryCore(c.getPackageName()))
            .map(Class::getName)
            .sorted()
            .toList();
    assertThat(offenders).as("query-core or non-exported types in exported signatures").isEmpty();
  }

  private static boolean isQueryCore(String packageName) {
    return packageName.equals("io.github.subapavel.jqj.query") || packageName.startsWith("io.github.subapavel.jqj.query.");
  }

  private static SortedSet<String> expectedLines() throws IOException {
    try (InputStream in = ExportedApiTest.class.getResourceAsStream(EXPECTED)) {
      assertThat(in).as("test resource %s", EXPECTED).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8)
          .lines()
          .map(String::strip)
          .filter(line -> !line.isEmpty() && !line.startsWith("#"))
          .collect(Collectors.toCollection(TreeSet::new));
    }
  }

  /** The module reference of jq-core, from the class-path entry that holds its module-info.class. */
  private static ModuleReference locateModule() throws IOException, URISyntaxException {
    Enumeration<URL> urls = ExportedApiTest.class.getClassLoader().getResources("module-info.class");
    while (urls.hasMoreElements()) {
      URL url = urls.nextElement();
      String name;
      try (InputStream in = url.openStream()) {
        name = ModuleDescriptor.read(in).name();
      }
      if (name.equals(MODULE)) {
        Path root =
            url.getProtocol().equals("jar")
                ? Path.of(((JarURLConnection) url.openConnection()).getJarFileURL().toURI())
                : Path.of(url.toURI()).getParent();
        return ModuleFinder.of(root).find(MODULE).orElseThrow(() -> new AssertionError("no module " + MODULE + " at " + root));
      }
    }
    throw new AssertionError("no module-info.class of " + MODULE + " on the class path");
  }

  /** The public types of the exported packages and what their signatures render to. */
  private record Surface(ModuleDescriptor descriptor, Set<String> exported, List<Class<?>> types) {
    static Surface of(ModuleReference module) throws IOException {
      ModuleDescriptor descriptor = module.descriptor();
      Set<String> exported =
          descriptor.exports().stream().filter(e -> !e.isQualified()).map(ModuleDescriptor.Exports::source).collect(Collectors.toSet());
      List<Class<?>> types = new ArrayList<>();
      try (ModuleReader reader = module.open();
          Stream<String> names = reader.list()) {
        for (String resource : names.sorted().toList()) {
          if (!resource.endsWith(".class") || resource.endsWith("module-info.class") || resource.endsWith("package-info.class")) {
            continue;
          }
          String binaryName = resource.substring(0, resource.length() - ".class".length()).replace('/', '.');
          int dot = binaryName.lastIndexOf('.');
          if (dot < 0 || !exported.contains(binaryName.substring(0, dot))) {
            continue;
          }
          Class<?> type = load(binaryName);
          if (isExportedType(type)) {
            types.add(type);
          }
        }
      }
      return new Surface(descriptor, exported, types);
    }

    private static Class<?> load(String binaryName) {
      try {
        return Class.forName(binaryName, false, ExportedApiTest.class.getClassLoader());
      } catch (ClassNotFoundException e) {
        throw new AssertionError("listed by the module reader but not loadable: " + binaryName, e);
      }
    }

    private static boolean isExportedType(Class<?> type) {
      if (type.isSynthetic() || type.isAnonymousClass() || type.isLocalClass()) {
        return false;
      }
      for (Class<?> c = type; c != null; c = c.getDeclaringClass()) {
        if (!Modifier.isPublic(c.getModifiers())) {
          return false;
        }
      }
      return true;
    }

    Set<String> unexportedPackages() {
      Set<String> hidden = new HashSet<>(descriptor.packages());
      hidden.removeAll(exported);
      return hidden;
    }

    SortedSet<String> lines() {
      SortedSet<String> lines = new TreeSet<>();
      moduleLines(lines);
      for (Class<?> type : types) {
        lines.add(type.getName() + SEPARATOR + typeLine(type));
        for (Member member : members(type)) {
          lines.add(type.getName() + SEPARATOR + memberLine(member));
        }
      }
      return lines;
    }

    private void moduleLines(Collection<String> lines) {
      String prefix = "module" + SEPARATOR;
      lines.add(prefix + words(descriptor.modifiers()) + "module " + descriptor.name());
      for (ModuleDescriptor.Requires r : descriptor.requires()) {
        lines.add(prefix + "requires " + words(r.modifiers()) + r.name());
      }
      for (ModuleDescriptor.Exports e : descriptor.exports()) {
        lines.add(prefix + "exports " + words(e.modifiers()) + e.source() + targets(e.targets()));
      }
      for (ModuleDescriptor.Opens o : descriptor.opens()) {
        lines.add(prefix + "opens " + words(o.modifiers()) + o.source() + targets(o.targets()));
      }
      for (String service : descriptor.uses()) {
        lines.add(prefix + "uses " + service);
      }
      for (ModuleDescriptor.Provides p : descriptor.provides()) {
        lines.add(prefix + "provides " + p.service() + " with " + String.join(", ", p.providers()));
      }
    }

    private static String words(Set<? extends Enum<?>> modifiers) {
      return modifiers.stream().map(m -> m.name().toLowerCase(Locale.ROOT) + " ").sorted().collect(Collectors.joining());
    }

    private static String targets(Set<String> targets) {
      return targets.isEmpty() ? "" : " to " + targets.stream().sorted().collect(Collectors.joining(", "));
    }

    private String typeLine(Class<?> type) {
      StringBuilder line = new StringBuilder(type.toGenericString());
      Type superclass = type.getGenericSuperclass();
      if (superclass != null && superclass != Object.class) {
        line.append(" extends ").append(superclass.getTypeName());
      }
      Type[] interfaces = type.getGenericInterfaces();
      if (interfaces.length > 0) {
        line.append(type.isInterface() ? " extends " : " implements ")
            .append(Arrays.stream(interfaces).map(Type::getTypeName).collect(Collectors.joining(", ")));
      }
      List<String> permits = exportedPermits(type);
      if (!permits.isEmpty()) {
        line.append(" permits ").append(String.join(", ", permits));
      }
      return line.toString();
    }

    private List<String> exportedPermits(Class<?> type) {
      Class<?>[] permitted = type.getPermittedSubclasses();
      if (permitted == null) {
        return List.of();
      }
      return Arrays.stream(permitted)
          .filter(p -> exported.contains(p.getPackageName()) && isExportedType(p))
          .map(Class::getName)
          .sorted()
          .toList();
    }

    private static List<Member> members(Class<?> type) {
      List<Member> members = new ArrayList<>();
      members.addAll(Arrays.asList(type.getDeclaredConstructors()));
      members.addAll(Arrays.asList(type.getDeclaredMethods()));
      members.addAll(Arrays.asList(type.getDeclaredFields()));
      members.removeIf(m -> m.isSynthetic() || !(Modifier.isPublic(m.getModifiers()) || Modifier.isProtected(m.getModifiers())));
      return members;
    }

    private static String memberLine(Member member) {
      if (member instanceof Executable executable) {
        return executable.toGenericString();
      }
      if (member instanceof Field field) {
        return field.toGenericString();
      }
      throw new AssertionError("unknown member kind: " + member);
    }

    /** Every class a rendered signature names: supertypes, exported permits, and member types. */
    Set<Class<?>> referencedTypes() {
      Set<Class<?>> out = new HashSet<>();
      Set<TypeVariable<?>> seen = new HashSet<>();
      for (Class<?> type : types) {
        collect(type.getGenericSuperclass(), out, seen);
        for (Type t : type.getGenericInterfaces()) {
          collect(t, out, seen);
        }
        for (TypeVariable<?> v : type.getTypeParameters()) {
          collect(v, out, seen);
        }
        for (Member member : members(type)) {
          switch (member) {
            case Method m -> {
              collect(m.getGenericReturnType(), out, seen);
              collectAll(m, out, seen);
            }
            case Constructor<?> c -> collectAll(c, out, seen);
            case Field f -> collect(f.getGenericType(), out, seen);
            default -> throw new AssertionError("unknown member kind: " + member);
          }
        }
      }
      return out;
    }

    private static void collectAll(Executable e, Set<Class<?>> out, Set<TypeVariable<?>> seen) {
      for (Type t : e.getGenericParameterTypes()) {
        collect(t, out, seen);
      }
      for (Type t : e.getGenericExceptionTypes()) {
        collect(t, out, seen);
      }
      for (TypeVariable<?> v : e.getTypeParameters()) {
        collect(v, out, seen);
      }
    }

    private static void collect(Type type, Set<Class<?>> out, Set<TypeVariable<?>> seen) {
      switch (type) {
        case null -> {}
        case Class<?> c when c.isArray() -> collect(c.getComponentType(), out, seen);
        case Class<?> c -> {
          if (!c.isPrimitive()) {
            out.add(c);
          }
        }
        case ParameterizedType p -> {
          collect(p.getRawType(), out, seen);
          collect(p.getOwnerType(), out, seen);
          for (Type a : p.getActualTypeArguments()) {
            collect(a, out, seen);
          }
        }
        case WildcardType w -> {
          for (Type b : w.getUpperBounds()) {
            collect(b, out, seen);
          }
          for (Type b : w.getLowerBounds()) {
            collect(b, out, seen);
          }
        }
        case GenericArrayType g -> collect(g.getGenericComponentType(), out, seen);
        case TypeVariable<?> v -> {
          if (seen.add(v)) {
            for (Type b : v.getBounds()) {
              collect(b, out, seen);
            }
          }
        }
        default -> throw new AssertionError("unknown reflective type: " + type);
      }
    }
  }
}
