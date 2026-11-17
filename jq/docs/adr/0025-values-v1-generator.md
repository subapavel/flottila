# values-v1: a frozen generator on its own SplitMix64

Status: accepted (2026-11-11, architect, on the designer's draft for #28 "S1.06 values-v1 generator"); amends ADR-0020 (generated case files, drops, the `values-v1` forms)

- **The generator.** `values-v1` is `…oracle.gen.ValuesV1`, a function from a seed to one case block
  that uses only the JDK. Its javadoc table is the parameter set (#28's evidence comment holds the
  approved table). It is frozen at the first merge of `diff/gen/values-v1.rec`: any change to an
  output byte makes it `values-v2`.
- **The PRNG** is our own SplitMix64 with the seed as its state, and every draw is derived with
  integers only. No `java.util.random`, `Random`, floating point, hash iteration order or locale
  formatting enters a case.
- **Forms.** The form is `seed % 7`, over seven fixed argument lists:

  | Form | Arguments |
  | --- | --- |
  | 0 | `-c .` |
  | 1 | `.` |
  | 2 | `-c -S .` |
  | 3 | `-c -a .` |
  | 4 | `-c tojson` |
  | 5 | `-c [paths]` |
  | 6 | `-c tostream` |

  ADR-0020's `-S`, `-a`, `tojson`, `[paths]` and `tostream` run compact. Under `-S` and `-a`,
  ADR-0015 turns the whole-container copy off only in compact mode, and form 1 already covers the
  pretty layout.
- **Seeds** are [0, 2100), 300 per form: 2.1× the 1000 floor, as margin for seeds excused later.
- **Documents.**
  - Each seed's stdin is exactly one value inside jq's accept set, at most 4096 bytes.
  - 20 % of documents carry lenient tokens.
  - Shapes that hit a listed deviation are excluded by construction: raw NUL, raw bytes below 0x20
    in strings, a lone high-surrogate escape, a BOM anywhere but byte 0.
  - A shape whose trial run on jq-ref gives rc ≠ 0 leaves the table before the freeze.
- **Files.** Generated families live in `diff/gen/<generator>.cases|.rec`. The family is the path
  relative to `diff/` and `diff/` is walked recursively, so ids read `gen/<generator>/<seed>`.
- **Drops.** A dropped seed (rc 3 or a timeout on jq-ref) is a `# dropped <seed> rc3|timeout` line in
  the case-file header. A seed that later hits a deviation keeps its block and gains `deviation` and
  `jqj-*` lines (ADR-0023). The freeze check (`generatedEqualsCommitted`) ignores those lines.

## Considered options

- `RandomGenerator.of("L64X128MixRandom")` or `SplittableRandom(long)`: neither promises the same
  sequence across releases (`SplittableRandom` only "in the same program").
- `java.util.Random`: a weak 48-bit LCG.
- A form drawn from the PRNG: the id would not show the form, and the counts per form would be uneven.
- 1400 seeds (200 per form): leaner, less margin.
- One flat `diff/gen.cases` for every generator; drops kept only in a log.
