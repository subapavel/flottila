#!/usr/bin/env bash
# Script test for the PIT test exclusions (#47, ADR-0014, ADR-0020). Each module keeps its excludedTestClasses in its
# own POM and nowhere else: a -DexcludedTestClasses on the command line replaces every module's POM list instead of
# adding to it (observed with -X, #45). The crossModule runs of jq-cli and oracle reach every class-C module.
# Needs Java 25 and the Maven wrapper (help:effective-pom).
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$here/../..
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0
check() { if [ "$2" != "$3" ]; then echo "FAIL $1: expected '$3' got '$2'"; fails=1; else echo "ok   $1"; fi; }

# --- no command-line list in the scripts that run PIT ---
for f in scripts/gate-spec.sh .github/workflows/nightly.yml; do
  check "no excludedTestClasses property in $f" "$(grep -c 'excludedTestClasses' "$repo/$f")" 0
done

# --- each module's effective list ---
# jq-cli: the module-path tests and the launcher test need surefire executions PIT does not run (jq-cli/pom.xml, #38).
# oracle: the Survey tests fail only on CRASH and stay out of PIT (ADR-0020); so does the compile-mode oracle, which
# runs in the ticket gate since #89 but never under PIT (F-46, ADR-0020 amendment for #60).
# jq-core and oracle: OperatorChainTest and WideConstructStepsTest are slow at each minion start and stay out of PIT
# (F-84 (a), #190); they still run in step 1. TailJumpFreshJvmTest runs its filters in child JVMs on the unmutated
# class path, so it kills no mutant (#118). Any further exclusion needs a finding. jq-core: the child-JVM classes of
# #113, CalibrationTest and DeepStackFreshJvmTest, whose mutants never reach a child (F-101); CalibrationRecordTest,
# in-process, stays in PIT's set.
expected() {
  case $1 in
    query-core) echo "" ;;
    jq-core) echo "io.github.subapavel.jqj.CalibrationTest,io.github.subapavel.jqj.DeepStackFreshJvmTest,io.github.subapavel.jqj.OperatorChainTest,io.github.subapavel.jqj.TailJumpFreshJvmTest,io.github.subapavel.jqj.WideConstructStepsTest" ;;
    jq-cli) echo "io.github.subapavel.jqj.cli.*ModulePathTest,io.github.subapavel.jqj.cli.LauncherTest" ;;
    oracle) echo "io.github.subapavel.jqj.OperatorChainTest,io.github.subapavel.jqj.TailJumpFreshJvmTest,io.github.subapavel.jqj.WideConstructStepsTest,io.github.subapavel.jqj.oracle.compile.CompileModeOracleTest,io.github.subapavel.jqj.oracle.compile.CompileModeSurveyTest,io.github.subapavel.jqj.oracle.diff.DifferentialSurveyTest,io.github.subapavel.jqj.oracle.jqtest.JqTestSurveyTest,io.github.subapavel.jqj.oracle.json.JsonSurveyTest" ;;
  esac
}
modules="query-core jq-core jq-cli oracle"
if ! "$repo/mvnw" -B -q -f "$repo/pom.xml" -pl "${modules// /,}" \
    org.apache.maven.plugins:maven-help-plugin:3.5.2:effective-pom "-Doutput=$tmp/effective.xml" > "$tmp/mvn.log" 2>&1 < /dev/null; then
  echo "FAIL help:effective-pom:"; sed 's/^/     /' "$tmp/mvn.log"
  exit 1
fi
# <module>\tproject, <module>\texcluded\t<param> and <module>\tdependency\t<artifactId> (the project's own
# dependencies, not those of dependencyManagement, build plugins or profiles) for every project of the effective POM
awk '
  function value(tag,   v) { v = $0; gsub(".*<" tag ">|</" tag ">.*", "", v); return v }
  /<project[ >]/ { name = ""; parent = 0; skip = 0; deps = 0 }
  /<parent>/ { parent = 1 }
  /<\/parent>/ { parent = 0 }
  !parent && name == "" && /<artifactId>/ { name = value("artifactId"); print name "\tproject" }
  /<(dependencyManagement|build|profiles)>/ { skip++ }
  /<\/(dependencyManagement|build|profiles)>/ { skip-- }
  !skip && /<dependencies>/ { deps = 1 }
  /<\/dependencies>/ { deps = 0 }
  deps && /<artifactId>/ { print name "\tdependency\t" value("artifactId") }
  /<excludedTestClasses>/ { inside = 1; next }
  /<\/excludedTestClasses>/ { inside = 0 }
  inside && /<param>/ { print name "\texcluded\t" value("param") }
' "$tmp/effective.xml" > "$tmp/params"
for m in $modules; do
  check "$m is in the effective POM" "$(grep -c "^$m	project\$" "$tmp/params")" 1
  got=$(awk -F'\t' -v m="$m" '$1 == m && $2 == "excluded" { print $3 }' "$tmp/params" | LC_ALL=C sort | paste -sd, -)
  check "$m effective excludedTestClasses" "$got" "$(expected "$m")"
done

# --- the crossModule runs reach every class-C module ---
# pitest-maven 1.30.2 mutates, besides the module's own classes, only the reactor modules the POM declares as
# dependencies (MojoToReportOptionsConverter.dependedOnProjects); a transitive one is not mutated.
classc=$(awk -F'\t' 'NR > 1 && $2 == "C" { print $1 }' "$repo/quality/floors.tsv")
for m in jq-cli oracle; do
  for c in $classc; do
    check "$m declares class-C module $c" "$(grep -c "^$m	dependency	$c\$" "$tmp/params")" 1
  done
done

exit $fails
