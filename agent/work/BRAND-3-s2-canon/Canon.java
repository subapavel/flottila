import io.flottila.mapping.api.*;
import io.flottila.mapping.structure.*;
import io.flottila.mapping.structure.importer.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Scratch, BRAND-3 step 2: writes the canonical text of a structure (the input of Structure.fingerprint) to a file. */
public class Canon {
  static String line(Field f) {
    return f.step() + " " + f.occurrence() + " " + f.valueType().map(ValueType::typeName).orElse("-") + " "
        + f.type().orElse("-") + (f.array() ? " array" : "")
        + f.refusal().map(r -> " refused:" + r.reason().code()).orElse("");
  }
  public static void main(String[] a) throws Exception {
    // args: name dir rootkind rootarg out files...
    Path dir = Path.of(a[1]);
    ReferenceResolver res = ref -> {
      try { return new ResolvedFile(ref, Files.readAllBytes(dir.resolve(ref))); }
      catch (java.io.IOException e) { throw new UnresolvedReferenceException(UnresolvedReferenceException.Reason.UNREADABLE, ref); }
    };
    RootSelector root = switch (a[2]) {
      case "element" -> new ElementRoot(a[3]);
      case "json" -> new JsonRoot();
      default -> { String[] s = a[3].split(" ");
        yield new WsdlRoot(new WsdlOperation(s[0], s[1], WsdlDirection.valueOf(s[2].toUpperCase()), s.length > 3 ? Optional.of(s[3]) : Optional.empty())); }
    };
    List<String> files = Arrays.asList(a).subList(5, a.length);
    StructureResult r = Structures.load(res, new SchemaBundle(files), root);
    Structure s = ((StructureResult.Loaded) r).structure();
    StringBuilder c = new StringBuilder("root ").append(line(s.root())).append('\n');
    for (StructureType t : new TreeMap<>(s.types()).values()) {
      c.append("type ").append(t.id()).append('\n');
      for (Field f : t.fields()) c.append("field ").append(line(f)).append('\n');
    }
    Files.writeString(Path.of(a[4]), c.toString());
    String mine = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(c.toString().getBytes("UTF-8")));
    System.out.println(a[0] + " engine=" + s.fingerprint() + " text=" + mine + (mine.equals(s.fingerprint()) ? " same" : " DIFFERENT"));
  }
}
