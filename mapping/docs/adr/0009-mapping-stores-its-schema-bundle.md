# A mapping stores its schema bundle; the structure is re-derived and guarded by a fingerprint

Status: accepted (2026-10-30, grilling #8); refined by ADR-0010 (the bundle is referenced, not copied); anonymous type ids since #62; amended by the spec-4 grilling (2026-11-05): what refresh reports, the compile check, fingerprint stability; array marker since #118

A mapping stores three things for each side:

- the **schema bundle**: the XSD, WSDL or JSON Schema files the structure was read from,
  verbatim (since ADR-0010 as references to files pinned by git or a package version);
- the **root selector**: an XML root element, a WSDL operation with its direction (and fault),
  or the JSON root;
- a **fingerprint**: SHA-256 over a canonical form of exactly what the compiler reads. That is
  the field paths, kinds, effective occurrences, value types, array markers and order. It leaves out display
  data, prefixes and whitespace.

Compiling at deploy re-reads the stored bundle and never a live schema, so a deploy is
reproducible. A fingerprint mismatch means a reader change produced a different structure. It
is a design-time error, and the author re-opens and re-saves the mapping.

Re-importing a new schema version compares the two structures by field path (ADR-0007):

- A path that is gone becomes an **orphaned reference**. It stays in the mapping model and the
  editor marks it. Compiling raises a design-time error with the `AuthoringId` of the box that
  uses it.
- A changed occurrence or value type is a warning.

Amended (spec-4 grilling, 2026-11-05): no old structure is stored (snapshots were rejected
below), and git has usually replaced the old schema file, so refresh cannot diff two
structures. Refresh:

1. re-derives both fingerprints;
2. lists the orphaned references, as `orphaned-reference` at the `authoringId` of the box or
   target field mapping that uses the path;
3. returns the stage-3 diagnostics of the refreshed working copy. A changed occurrence or value
   type shows through its real consequences, such as `unmapped-mandatory-leaf` or a failing cast.

There is no occurrence or type-change warning. Compile (stage 3) re-reads the bundle and
compares fingerprints. A mismatch is `fingerprint-mismatch` at `/source/fingerprint` or
`/target/fingerprint`, and it blocks compile and test. Only refresh re-derives a fingerprint;
Save never does (ADR-0010).

A field's refusal (ADR-0008) is part of the canonical text (#92):
- A refused field's canonical line ends in ` refused:<reason code>`. A field without a refusal
  keeps its line.
- The refusal's file, line and column never enter it. As with whitespace, moving a declaration
  changes no fingerprint, and compile, which re-reads the bundle, names the current position.

A JSON array property (ADR-0008, #118) is part of the canonical text:
- Its line has ` array` after its child type, before any ` refused:<reason code>`. Every other
  field keeps its line, so no XSD or WSDL fingerprint changes.
- A JSON structure with an array property changes its fingerprint once, with #118. Those pins
  were added in spec 4 and never reached `main`, so no released fingerprint changes.

Extending the reader must not change
the canonical text of a structure it already read: a test pins the fingerprints of the spec-2
subset as literals. A change that must alter them says so in its ticket, and the corpus mappings
are refreshed through the refresh path, never edited by hand.

An anonymous complex type has no name of its own, so its canonical type id is the field path
of the element that declares it. Sibling structure fields that share one step get ids `path`,
`path#2`, `path#3` in document order (#62). The suffix is part of the canonical text, so reordering
such siblings changes the fingerprint; that is intended, because their order is what the compiler reads.

A JSON Schema type id is `<bundle reference>#<JSON pointer>` (#97): it holds the file's reference as
the bundle spells it, so renaming or moving a JSON bundle file changes the fingerprint even when
its content does not. That is accepted. The bundle reference is what compile re-reads (ADR-0010),
and a moved file is a refresh like any other schema change. XSD type ids are QNames or field paths
and do not depend on the spelling.

How the bundle sits inside the mapping model is decided by ADR-0010 (#6). The mapping refers to
the bundle files by a path relative to the mapping file, or by a file in a shared package at a
fixed version. A git commit and the pinned versions keep the bundle verbatim and the deploy
reproducible, and the model holds no copy. A *refresh* re-derives the fingerprint and lists the
orphaned references.

## Considered options

- **Reading the live schema at compile time**: rejected. Deploys would not be reproducible, and
  a schema edit would silently change running mappings.
- **Persisting the normalised structure as a snapshot**: rejected. It is a second format to
  version, and it would hide reader changes instead of detecting them.
