# Rail8 jq

An implementation of the [jq](https://jqlang.org/) language on Java 25. A filter is compiled once
and run directly over JSON bytes, without building an object tree of the input. Built for Rail8,
which runs the same filter on many messages, where speed and bounded memory matter; usable from
the command line and embedded as a library.

It started as the standalone project `subapavel/jqj` and continues here with its history. Its
first two phases are done: the walking skeleton (phase 1) and the core language with deep
recursion (phase 2). `docs/FOUNDING-ARCHITECTURE.md` is the direction, `docs/adr/` the decisions,
`bench/RESULTS.md` the measurements. The process files of the standalone project are in
`docs/archive/`.

Build from the repository's root with `./mvnw -B -f jq/pom.xml verify`, or from `jq/` with
`../mvnw -B verify`.

This is an independent implementation and is not affiliated with the jq project.

Licence: Apache 2.0 (`LICENSE`). Third-party notices: `NOTICE`.
