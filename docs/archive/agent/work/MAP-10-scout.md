# MAP-10 - why the tests of `engine/structure-import` do not pass under PIT (a scout, 2026-11-16; haiku;
# written down by the architect)

- `agent/work/MAP-10-pit-structure-import.log`: 126 cases "did not pass without mutation", in all six test classes
  of the module (JsonSchemaReaderTest 31, StructureReaderTest 74, XsdFilesAndNamesTest 8, XsdModelGroupsTest 5,
  WsdlReaderTest 4, XsdDerivationTest 4); the log prints no exception.
- All six read a system property for their fixtures: `Path.of(System.getProperty("rail8.corpus"), ...)`
  (`JsonSchemaReaderTest.java`:35-36, `StructureReaderTest.java`:39, `WsdlReaderTest.java`:36,
  `XsdDerivationTest.java`:42, `XsdFilesAndNamesTest.java`:38, `XsdModelGroupsTest.java`:37). No resource loading,
  no working-directory path.
- Surefire sets it: `mapping/engine/structure-import/pom.xml`:57-63 `systemPropertyVariables`,
  `<rail8.corpus>${mapping.root}/corpus</rail8.corpus>` (`mapping/pom.xml`:34 defines `mapping.root`).
- PIT's configuration (`mapping/pom.xml`:126-148) has no `jvmArgs` and nothing that hands a property to its child
  JVMs. Inferred: the property is null there and the static initializer of each class throws.
- The plugin's own descriptor (pitest-maven 1.30.1, `META-INF/maven/plugin.xml`, read by the architect): `jvmArgs`
  has the user property `${jvmArgs}` - the property can be handed over for one run with `-DjvmArgs=-Drail8.corpus=...`.
- On record: the mapping's F-10 (:4-5, PIT runs on the class path; `engine/api` excludes one test from PIT, its
  `pom.xml`:51-53); F-5 :15 (a `pit.skip` property skips `structure`, `structure-import`, `oracle`, `service`). Nothing
  names `rail8.corpus` and PIT together.
- `engine/structure`'s tests do not read the property; it ran under PIT as it is.
