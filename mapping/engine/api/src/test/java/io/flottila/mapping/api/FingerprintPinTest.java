package io.flottila.mapping.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import io.flottila.mapping.mapping.LoadResult;
import io.flottila.mapping.mapping.Loaded;
import io.flottila.mapping.mapping.MappingModel;
import io.flottila.mapping.mapping.MappingModelFormat;
import io.flottila.mapping.mapping.StructureRef;
import io.flottila.mapping.structure.ElementRoot;
import io.flottila.mapping.structure.JsonRoot;
import io.flottila.mapping.structure.SchemaBundle;
import io.flottila.mapping.structure.WsdlDirection;
import io.flottila.mapping.structure.WsdlOperation;
import io.flottila.mapping.structure.WsdlRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The fingerprint pin (ADR-0009 as amended on 2026-11-05, #91): the fingerprint of every structure
 * a corpus mapping uses, the spec-2 subset among them, as a literal. Compile refuses a stored
 * fingerprint that differs from its bundle's, so a reader change that alters the canonical text of
 * one of these structures would refuse every corpus mapping over it; it fails here first, by name.
 */
class FingerprintPinTest {

  /** By the bundle file under {@code corpus/structures/} and the root element, a space between. */
  private static final Map<String, String> PINNED =
      Map.ofEntries(
          // #118: each JSON array property's canonical line has " array" after its child type
          // (ADR-0009), so the two JSON pins of spec 4 change once; every XSD and WSDL pin keeps
          // its literal. The values are SHA-256 over the canonical text written by hand, a method
          // that reproduces the earlier literals 3489768… and c4c151c… from the unmarked text.
          entry(
              "json-schema/run-input.schema.json $",
              "sha256:105d98ae878bb024b38d0a0dd69e349b563ff00572ce7e5e3fa27bd88ac8afe9"),
          entry(
              "observations/nil.schema.json $",
              "sha256:88f31d654678c63609b0643c7175af239bb68f36962ffaee980d17722f045ef2"),
          // #118: one, none, maybe and objs are array properties at 0..1, 0..0, 0..1 and 0..1,
          // each line with " array"; plain and objs/sku keep their lines
          entry(
              "json-schema/short-arrays.schema.json $",
              "sha256:46f3c45a7b2abea8d85365d26a2e2e3fae15a1c486b7b8062bbe6fe043ac8247"),
          entry(
              "observations/src-orders.xsd Orders",
              "sha256:5e9bdd3db3b284fc086f0233797ede5e4f3fb874bd696ba3f496dd30bffef9a7"),
          entry(
              "observations/tgt-attr.xsd Flagged",
              "sha256:b56d01b2f49ebd527be872f30b54d71b988318a613bd035d7c04148e656c739b"),
          entry(
              "observations/tgt-create.xsd Create",
              "sha256:c55781b0218fbd5796a6bb9e47dda32fbd3cb7129744178ee56130aeaa3c986f"),
          entry(
              "observations/tgt-gen.xsd Gens",
              "sha256:4a92b5bce393664363954017c2b1029537235b8a4557f9a2a220c66d516f4e35"),
          entry(
              "observations/tgt-list.xsd Out",
              "sha256:626cbacff639a86b480bb3c20cc032d8aa47ad341d9111e31882ad30ca6eca70"),
          entry(
              "observations/tgt-nested.xsd Doc",
              "sha256:b332e167f64d36905b0ae08963a21b6d358bc7e29454797506150420956ac187"),
          entry(
              "observations/tgt-parents.xsd Parents",
              "sha256:2f05337dcfccb424e2a353eb4b124a3641ae56c154a742b5ba2e77c95c7c502d"),
          entry(
              "observations/tgt-required-attr.xsd Coded",
              "sha256:231ac22e1a8229cce19e6ab94298d8843aa7da573fbe9b8a5d844a5b05d112b7"),
          entry(
              "observations/tgt-unmapped.xsd Unmapped",
              "sha256:bb054118c01ca0072e0e8be95386b7c8fb4c8ab366cbd7f92bff8debbded1706"),
          entry(
              "skeleton/order.xsd Q{urn:flottila:corpus:skeleton}Order",
              "sha256:df1714b8b2e4b09e326626a2ea72780bf6298a0ca733c69f5586ea657e4bd90d"),
          // #92: its two Notes and two Refs are refused fields, whose canonical lines end in
          // " refused:ambiguous-field-path" (ADR-0009 as refined by #92)
          entry(
              "refused/same-step.xsd Doc",
              "sha256:d26971c5a6f2df7fa0bf9f7287ae22f1e93ef12dd6ea4ac8ce748990ba0ec32e"),
          // #95: derivation, substitution and an abstract element; the abstract Remark's
          // canonical line ends in " refused:abstract-target-element"
          entry(
              "derivation/order.xsd Q{urn:flottila:corpus:order}Order",
              "sha256:7fa870df17665f949da9986b2036fc81a4299fe38c920afdd24b20ac29b17284"),
          entry(
              "derivation/order.xsd Q{urn:flottila:corpus:order}Receipt",
              "sha256:f7b3d1697015e4f93e73caa19b269e7cbd44f8b19146ba6b50c49bdb7bfa5c84"),
          // #94: a choice (str-choice-1, -2), a repeated choice of elements, and a repeated
          // sequence whose A and B are refused fields ending in " refused:interleaved-target-group"
          entry(
              "observations/tgt-choice.xsd Choice",
              "sha256:49a6269dbb5b409cc061dc9e732e4e2142ecf725ba411e29a1949b0eea5048bf"),
          entry(
              "model-groups/picks.xsd Picks",
              "sha256:f3ba6b25507a1013629b1aa81257e12ce4fc903aa0fac19f5a59fb3c66612ae2"),
          entry(
              "model-groups/pairs.xsd Pairs",
              "sha256:aa69e5c99ad5d331ec99c143eb35502c33870cb818b29b4c974c97c11d37fdc2"),
          // #100 (GATE check 1): $defs/$ref, allOf, oneOf, an array of objects and a nullable type
          // list; SHA-256 over the canonical text written by hand, the method that reproduces
          // 105d98… (run-input) and 7fa870… (derivation/order.xsd Order) above
          entry(
              "gate/order.schema.json $",
              "sha256:ee54dcfa5f2ce0e42c2cbae54b270f17d2a89877141513ca5a9b8a3944df7f5d"));

  /**
   * Multi-file element roots (#100): by the bundle files under {@code corpus/structures/} in the
   * order a canonical model stores them (sorted), a space between, then {@code |} and the root
   * element. The values are SHA-256 over the canonical text written by hand, as for {@link
   * #PINNED}.
   */
  private static final Map<String, String> PINNED_BUNDLES =
      Map.ofEntries(
          // an include, an import of a second namespace, named and anonymous types, an
          // extension, a choice, a substitution group and a simple-content leaf with attributes
          entry(
              "gate/purchase-order/common/common.xsd gate/purchase-order/order.xsd"
                  + " gate/purchase-order/types.xsd"
                  + " | Q{urn:flottila:corpus:gate:po}PurchaseOrder",
              "sha256:c7a83f28ce9966a4cb158317dc7a29a6e89c59ec6ed129bbd3317aa50a7293f3"),
          // a second namespace, an element reference across files and a recursive type
          entry(
              "gate/invoice/invoice.xsd gate/invoice/party.xsd"
                  + " | Q{urn:flottila:corpus:gate:invoice}Invoice",
              "sha256:58de05eec9c995263cd630786bcdecf224806c5e23cdecad4681bea25648ea4a"));

  /** The root of a key whose structure is a JSON root (#98). */
  private static final String JSON_ROOT = "$";

  /**
   * WSDL roots (#96): by the bundle files under {@code corpus/structures/}, a space between, then
   * {@code |} and the selector ({@code <interface> <operation> <direction> [<fault>]}).
   */
  private static final Map<String, String> PINNED_WSDL =
      Map.ofEntries(
          entry(
              "files-and-names/import-location/common/types.xsd"
                  + " files-and-names/import-location/po/order.xsd wsdl/order-11.wsdl"
                  + " | Q{urn:flottila:corpus:wsdl11}OrderPort Submit input",
              "sha256:510d96ec830e4ad8aa2e477a6fe73640f58587cc84ce10ec68ce666ca65edd16"),
          entry(
              "files-and-names/import-location/common/types.xsd"
                  + " files-and-names/import-location/po/order.xsd wsdl/order-11.wsdl"
                  + " | Q{urn:flottila:corpus:wsdl11}OrderPort Submit output",
              "sha256:facdda4e20e0833ad276202aa9f4c563264b3b071e5c12825ba01082ba1cbc85"));

  /** The prefix of a corpus mapping's bundle reference: mappings sit beside structures. */
  private static final String STRUCTURES = "../structures/";

  @Test
  void every_pinned_structure_loads_with_its_literal_fingerprint() {
    // loaded as a corpus mapping names it, from corpus/mappings: a JSON structure's type ids hold
    // its file as the bundle spells it (#97), so its fingerprint does too
    Path mappings =
        CorpusStructures.directory("json-schema").getParent().resolveSibling("mappings");
    Map<String, String> loaded = new TreeMap<>();
    for (String key : PINNED.keySet()) {
      String[] fileAndRoot = key.split(" ", 2);
      StructureResult result =
          Structures.load(
              DirectoryResolver.over(mappings),
              new SchemaBundle(List.of(STRUCTURES + fileAndRoot[0])),
              fileAndRoot[1].equals(JSON_ROOT) ? new JsonRoot() : new ElementRoot(fileAndRoot[1]));
      assertThat(result).as(key).isInstanceOf(StructureResult.Loaded.class);
      loaded.put(key, ((StructureResult.Loaded) result).structure().fingerprint());
    }

    assertThat(loaded).isEqualTo(new TreeMap<>(PINNED));
  }

  @Test
  void every_pinned_WSDL_structure_loads_with_its_literal_fingerprint() {
    Path structures = CorpusStructures.directory("wsdl").getParent();
    Map<String, String> loaded = new TreeMap<>();
    for (String key : PINNED_WSDL.keySet()) {
      String[] filesAndSelector = key.split(" \\| ", 2);
      String[] selector = filesAndSelector[1].split(" ");
      WsdlDirection direction = WsdlDirection.valueOf(selector[2].toUpperCase(Locale.ROOT));
      Optional<String> fault = selector.length > 3 ? Optional.of(selector[3]) : Optional.empty();
      StructureResult result =
          Structures.load(
              DirectoryResolver.over(structures),
              new SchemaBundle(List.of(filesAndSelector[0].split(" "))),
              new WsdlRoot(new WsdlOperation(selector[0], selector[1], direction, fault)));
      assertThat(result).as(key).isInstanceOf(StructureResult.Loaded.class);
      loaded.put(key, ((StructureResult.Loaded) result).structure().fingerprint());
    }

    assertThat(loaded).isEqualTo(new TreeMap<>(PINNED_WSDL));
  }

  @Test
  void every_pinned_multi_file_structure_loads_with_its_literal_fingerprint() {
    Path structures = CorpusStructures.directory("wsdl").getParent();
    Map<String, String> loaded = new TreeMap<>();
    for (String key : PINNED_BUNDLES.keySet()) {
      String[] filesAndRoot = key.split(" \\| ", 2);
      StructureResult result =
          Structures.load(
              DirectoryResolver.over(structures),
              new SchemaBundle(List.of(filesAndRoot[0].split(" "))),
              new ElementRoot(filesAndRoot[1]));
      assertThat(result).as(key).isInstanceOf(StructureResult.Loaded.class);
      loaded.put(key, ((StructureResult.Loaded) result).structure().fingerprint());
    }

    assertThat(loaded).isEqualTo(new TreeMap<>(PINNED_BUNDLES));
  }

  /**
   * Every side of every corpus mapping names a pinned structure and stores its pinned fingerprint,
   * so a corpus mapping over a structure this test does not pin fails here.
   */
  @Test
  void every_structure_a_corpus_mapping_uses_is_pinned_with_its_stored_fingerprint()
      throws Exception {
    Map<String, String> stale = new TreeMap<>();
    List<Path> mappings = corpusMappings();
    assertThat(mappings).as("corpus mappings").isNotEmpty();
    for (Path mapping : mappings) {
      MappingModel model = model(mapping);
      // every corpus mapping has both its structures; one without would fail here, not be skipped
      for (StructureRef side :
          List.of(model.source().orElseThrow(), model.target().orElseThrow())) {
        String key = key(side);
        if (!side.fingerprint().equals(pinned(key))) {
          stale.put(mapping.getFileName() + " " + key, side.fingerprint());
        }
      }
    }

    // the corpus's two deliberately stale mappings, and nothing else (structure/fingerprint/*)
    assertThat(stale)
        .containsOnlyKeys(
            "fingerprint-stale-source.mapping.json skeleton/order.xsd"
                + " Q{urn:flottila:corpus:skeleton}Order",
            "fingerprint-stale-target.mapping.json skeleton/order.xsd"
                + " Q{urn:flottila:corpus:skeleton}Order");
  }

  /** The pinned fingerprint of a key of any of the three maps, or null when none pins it. */
  private static String pinned(String key) {
    return PINNED.getOrDefault(key, PINNED_WSDL.getOrDefault(key, PINNED_BUNDLES.get(key)));
  }

  private static String key(StructureRef side) {
    List<String> files = side.bundle().files();
    if (side.root() instanceof WsdlRoot(WsdlOperation operation)) {
      return wsdlKey(files, operation);
    }
    // a JSON source's root is $ (#98)
    String root =
        side.root() instanceof JsonRoot ? JSON_ROOT : ((ElementRoot) side.root()).element();
    assertThat(files)
        .as("a corpus bundle under corpus/structures")
        .allMatch(f -> f.startsWith(STRUCTURES));
    String bundle =
        String.join(" ", files.stream().map(f -> f.substring(STRUCTURES.length())).toList());
    // a key of PINNED_BUNDLES (#100), or of PINNED
    return files.size() > 1 ? bundle + " | " + root : bundle + " " + root;
  }

  /** A key of {@link #PINNED_WSDL}. */
  private static String wsdlKey(List<String> files, WsdlOperation operation) {
    assertThat(files)
        .as("a WSDL bundle under corpus/structures")
        .allMatch(f -> f.startsWith(STRUCTURES));
    return String.join(" ", files.stream().map(f -> f.substring(STRUCTURES.length())).toList())
        + " | "
        + operation.interfaceName()
        + " "
        + operation.operation()
        + " "
        + operation.direction().name().toLowerCase(Locale.ROOT)
        + operation.fault().map(f -> " " + f).orElse("");
  }

  private static MappingModel model(Path mapping) throws Exception {
    LoadResult result = MappingModelFormat.load(Files.readAllBytes(mapping));
    assertThat(result).as("%s", mapping).isInstanceOf(Loaded.class);
    return ((Loaded) result).model();
  }

  /** Every {@code corpus/mappings/*.mapping.json}, sorted. */
  private static List<Path> corpusMappings() throws IOException {
    Path mappings = CorpusStructures.directory("skeleton").getParent().resolveSibling("mappings");
    try (Stream<Path> files = Files.list(mappings)) {
      return files
          .filter(p -> p.getFileName().toString().endsWith(".mapping.json"))
          .sorted()
          .toList();
    }
  }
}
