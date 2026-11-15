#!/usr/bin/env bash
# Script test for scripts/function-families.java (#69, ADR-0025 "Families", F-15): the build-time
# generator that merges the function families. It runs the generator on temp trees built from the
# fixtures in scripts/test/function-families/:
#
#   java scripts/function-families.java catalogue <function-catalogue.json> <input>...
#   java scripts/function-families.java helper <po-runtime.xsl> <input>...
#   java scripts/function-families.java index <the index's Java source> <input>...
#
# An input is a directory, whose files the generator lists as the build passes it (the family
# files <family>.json; core.xsl and the modules <family>.xsl; the runtime sources, of which the
# <Family>Family.java count), or one such file. Exit 0 means the output is written; any other exit
# fails the build. The order of the files, listed or given, never shows in an output; the test
# gives them in seeded orders, so it does not depend on how a filesystem lists a directory.
# The fixture families are alpha, beta and gamma: their names interleave (apple banana cherry kiwiB
# kiwia melon melonBall zucchini, in UTF-16 order), and alpha and beta sort before core.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
tool=$here/../function-families.java
fixtures=$here/function-families
java=${JAVA_HOME:+$JAVA_HOME/bin/}java
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0
check() { if [ "$2" != "$3" ]; then echo "FAIL $1: expected '$3' got '$2'"; fails=1; else echo "ok   $1"; fi; }

# add <tree> <family>: the family's three files, one per directory
add() {
  local type
  type="$(printf '%s' "${2:0:1}" | tr '[:lower:]' '[:upper:]')${2:1}Family"
  cp "$fixtures/catalogue/$2.json" "$1/catalogue/"
  cp "$fixtures/po-runtime/$2.xsl" "$1/po-runtime/"
  cp "$fixtures/runtime/$type.java" "$1/runtime/"
}
# tree <family>...: a fresh tree with core.xsl, a runtime source that is no family file, and the
# families' files; prints its path
tree() {
  local t f
  t=$(mktemp -d -p "$tmp")
  mkdir -p "$t/catalogue" "$t/po-runtime" "$t/runtime" "$t/out"
  cp "$fixtures/po-runtime/core.xsl" "$t/po-runtime/"
  cp "$fixtures/runtime/Projection.java" "$t/runtime/"
  for f in "$@"; do add "$t" "$f"; done
  echo "$t"
}
# generate <output dir> <mode> <input>...: the generator into the mode's output in <output dir>
# (function-catalogue.json, po-runtime.xsl, FamilyIndex.java), its stdout and stderr into
# <output dir>/<mode>.log; prints the exit code
generate() {
  local dir=$1 mode=$2 out
  shift 2
  case $mode in
    catalogue) out=function-catalogue.json ;;
    helper) out=po-runtime.xsl ;;
    index) out=FamilyIndex.java ;;
  esac
  mkdir -p "$dir"
  "$java" "$tool" "$mode" "$dir/$out" "$@" > "$dir/$mode.log" 2>&1
  echo $?
}
# run <tree> <mode>: the generator over the mode's directory, as the build runs it, into <tree>/out
run() {
  case $2 in
    catalogue) generate "$1/out" "$2" "$1/catalogue" ;;
    helper) generate "$1/out" "$2" "$1/po-runtime" ;;
    index) generate "$1/out" "$2" "$1/runtime" ;;
  esac
}
failed() { if [ "$1" = 0 ]; then echo 0; else echo non-zero; fi; }
same() { if cmp -s "$1" "$2"; then echo same; else echo differ; fi; }
# sources <tree>: every source file of the tree with its checksum, the outputs left out
sources() { (cd "$1" && find catalogue po-runtime runtime -type f | LC_ALL=C sort | while read -r f; do echo "$f $(cksum < "$f")"; done); }

# the names of the xsl:function elements of a module, in document order
functions() { grep -o '<xsl:function name="[^"]*"' "$1" 2>/dev/null | sed 's/.*name="\([^"]*\)"/\1/' | tr '\n' ' '; }
# the lines of a module up to its root start tag, and the non-blank lines inside its root
prolog() { awk '{ print } /exclude-result-prefixes="#all">/ { exit }' "$1" 2>/dev/null; }
body() { awk 'open && /^<\/xsl:stylesheet>/ { exit } open && NF { print } /exclude-result-prefixes="#all">/ { open = 1 }' "$1" 2>/dev/null; }
# the types of $2... that the index names
names() { local out=$1 t; shift; for t in "$@"; do if grep -qw "$t" "$out" 2>/dev/null; then printf '%s ' "$t"; fi; done; }

# helper_is <check> <po-runtime.xsl> <family>...: core.xsl, then the families' modules in that order
helper_is() {
  local c=$1 out=$2 f
  shift 2
  local core=$fixtures/po-runtime/core.xsl
  check "$c: core's prolog and root start tag begin it" "$(prolog "$out")" "$(prolog "$core")"
  check "$c: core's content, then each family's" "$(body "$out")" \
    "$(body "$core"; for f in "$@"; do body "$fixtures/po-runtime/$f.xsl"; done)"
  check "$c: every xsl:function once, in that order" "$(functions "$out")" \
    "$(functions "$core")$(for f in "$@"; do functions "$fixtures/po-runtime/$f.xsl"; done)"
  check "$c: one XML declaration and one root" \
    "$(grep -c -e '<?xml ' -e '<xsl:stylesheet ' -e '</xsl:stylesheet>' "$out" 2>/dev/null)" 3
  check "$c: the root's end tag and a final LF end it" "$(tail -c 18 "$out" 2>/dev/null | tr '\n' '|')" \
    '</xsl:stylesheet>|'
}

aNewFamilyNeedsOnlyItsOwnFiles() {
  local c=aNewFamilyNeedsOnlyItsOwnFiles t m
  t=$(tree alpha beta)
  for m in catalogue helper index; do check "$c: alpha, beta: $m exit" "$(run "$t" $m)" 0; done
  check "$c: alpha, beta: catalogue" "$(same "$fixtures/expected/alpha-beta.json" "$t/out/function-catalogue.json")" same
  helper_is "$c: alpha, beta: helper" "$t/out/po-runtime.xsl" alpha beta
  check "$c: alpha, beta: index" "$(names "$t/out/FamilyIndex.java" AlphaFamily BetaFamily GammaFamily Projection)" \
    "AlphaFamily BetaFamily "

  # gamma's three files, and nothing else, added
  add "$t" gamma
  for m in catalogue helper index; do check "$c: gamma added: $m exit" "$(run "$t" $m)" 0; done
  check "$c: gamma added: catalogue" "$(same "$fixtures/expected/alpha-beta-gamma.json" "$t/out/function-catalogue.json")" same
  helper_is "$c: gamma added: helper" "$t/out/po-runtime.xsl" alpha beta gamma
  check "$c: gamma added: index" "$(names "$t/out/FamilyIndex.java" AlphaFamily BetaFamily GammaFamily Projection)" \
    "AlphaFamily BetaFamily GammaFamily "
  # the generator writes its outputs only
  check "$c: the sources are the fixtures, unchanged" "$(sources "$t")" "$(sources "$(tree alpha beta gamma)")"
}

# shuffle <array name>: the array in a Fisher-Yates order of the seeded RANDOM, in place (no
# subshell, so that every call goes on with the same sequence)
shuffle() {
  local -n items=$1
  local j k swap
  for ((j = ${#items[@]} - 1; j > 0; j--)); do
    k=$((RANDOM % (j + 1)))
    swap=${items[j]}
    items[j]=${items[k]}
    items[k]=$swap
  done
}
# byName <item>...: "yes" when the items are in name order
byName() { if [ "$(printf '%s\n' "$@")" = "$(printf '%s\n' "$@" | LC_ALL=C sort)" ]; then echo yes; else echo no; fi; }

theMergeIgnoresListingOrder() {
  local c=theMergeIgnoresListingOrder t i out exits="" catalogues="" helpers="" indexes="" outOfOrder=""
  t=$(tree alpha beta gamma)
  # the directories, as the build passes them: what every order of the files must give
  exits+="$(run "$t" catalogue) $(run "$t" helper) $(run "$t" index) "
  local -a catalogue=("$t/catalogue/alpha.json" "$t/catalogue/beta.json" "$t/catalogue/gamma.json")
  local -a helper=("$t/po-runtime/alpha.xsl" "$t/po-runtime/beta.xsl" "$t/po-runtime/core.xsl" "$t/po-runtime/gamma.xsl")
  local -a index=("$t/runtime/AlphaFamily.java" "$t/runtime/BetaFamily.java" "$t/runtime/GammaFamily.java")
  RANDOM=69
  for ((i = 1; i <= 8; i++)); do
    shuffle catalogue
    shuffle helper
    shuffle index
    if [ "$(byName "${catalogue[@]}")" = no ]; then outOfOrder+="catalogue "; fi
    if [ "$(byName "${helper[@]}")" = no ]; then outOfOrder+="helper "; fi
    if [ "$(byName "${index[@]}")" = no ]; then outOfOrder+="index "; fi
    out=$t/given$i
    exits+="$(generate "$out" catalogue "${catalogue[@]}") "
    exits+="$(generate "$out" helper "${helper[@]}") "
    exits+="$(generate "$out" index "${index[@]}") "
    catalogues+="$(same "$fixtures/expected/alpha-beta-gamma.json" "$out/function-catalogue.json") "
    helpers+="$(same "$t/out/po-runtime.xsl" "$out/po-runtime.xsl") "
    indexes+="$(same "$t/out/FamilyIndex.java" "$out/FamilyIndex.java") "
  done
  # witness: every mode got its files out of name order in some run, so an order would show
  check "$c: witness: the modes given their files out of name order" \
    "$(for m in catalogue helper index; do case " $outOfOrder" in *" $m "*) printf '%s ' $m ;; esac; done)" \
    "catalogue helper index "
  check "$c: every exit" "$exits" "$(printf '0 %.0s' {1..27})"
  check "$c: the directory's catalogue is the canonical merge" \
    "$(same "$fixtures/expected/alpha-beta-gamma.json" "$t/out/function-catalogue.json")" same
  helper_is "$c: the directory's helper" "$t/out/po-runtime.xsl" alpha beta gamma
  check "$c: the directory's index" "$(names "$t/out/FamilyIndex.java" AlphaFamily BetaFamily GammaFamily Projection)" \
    "AlphaFamily BetaFamily GammaFamily "
  check "$c: every order's catalogue is the canonical merge" "$catalogues" "$(printf 'same %.0s' {1..8})"
  check "$c: every order's helper module is the directory's, byte for byte" "$helpers" "$(printf 'same %.0s' {1..8})"
  check "$c: every order's index is the directory's, byte for byte" "$indexes" "$(printf 'same %.0s' {1..8})"
}

aNameInTwoFamiliesFailsTheBuild() {
  local c=aNameInTwoFamiliesFailsTheBuild t name
  # apple is alpha's, zucchini beta's: the first and the last name
  for name in apple zucchini; do
    t=$(tree alpha beta)
    check "$c: witness: alpha, beta build" "$(run "$t" catalogue)" 0
    printf '{\n  "%s": {\n    "executionKind": "single",\n    "portable": true\n  }\n}\n' "$name" \
      > "$t/catalogue/delta.json"
    check "$c: $name in delta.json too: exit" "$(failed "$(run "$t" catalogue)")" non-zero
    check "$c: $name in delta.json too: the message names it" \
      "$(grep -qw "$name" "$t/out/catalogue.log" && echo yes || echo no)" yes
  done
}

# gamma.json, valid JSON but not in canonical form (ADR-0010) in one way
notCanonical() {
  local canonical=$fixtures/catalogue/gamma.json
  case $1 in
    unsortedMembers)
      printf '{\n  "cherry": {\n    "executionKind": "single",\n    "portable": true,\n    "inputs": [\n      {\n        "name": "value"\n      }\n    ]\n  }\n}\n' ;;
    unsortedNames)
      printf '{\n  "date": {\n    "executionKind": "single",\n    "portable": true\n  },\n  "cherry": {\n    "executionKind": "single",\n    "portable": true\n  }\n}\n' ;;
    emptyArray)
      printf '{\n  "cherry": {\n    "executionKind": "single",\n    "inputs": [\n      {\n        "name": "value"\n      }\n    ],\n    "params": [],\n    "portable": true\n  }\n}\n' ;;
    noFinalLf) printf '%s' "$(cat "$canonical")" ;;
    crlf) sed 's/$/\r/' "$canonical" ;;
    fourSpaces) sed 's/^\( *\)/\1\1/' "$canonical" ;;
    bom) printf '\357\273\277'; cat "$canonical" ;;
    compact) tr -d ' \n' < "$canonical"; echo ;;
  esac
}

aFamilyFileNotCanonicalFailsTheBuild() {
  local c=aFamilyFileNotCanonicalFailsTheBuild t way
  t=$(tree alpha beta gamma)
  # witness: canonical files build, beta's {} table default and escapes included
  check "$c: witness: alpha, beta, gamma build" "$(run "$t" catalogue)" 0
  check "$c: witness: the merge" "$(same "$fixtures/expected/alpha-beta-gamma.json" "$t/out/function-catalogue.json")" same
  for way in unsortedMembers unsortedNames emptyArray noFinalLf crlf fourSpaces bom compact; do
    t=$(tree alpha beta gamma)
    notCanonical $way > "$t/catalogue/gamma.json"
    check "$c: gamma.json $way: exit" "$(failed "$(run "$t" catalogue)")" non-zero
    check "$c: gamma.json $way: the message names the file" \
      "$(grep -q 'gamma\.json' "$t/out/catalogue.log" && echo yes || echo no)" yes
  done
}

# beta.xsl with a root start tag other than core's, byte for byte, in one way
otherRootTag() {
  local module=$fixtures/po-runtime/beta.xsl
  case $1 in
    extraNamespace)
      awk '{ print } /^    xmlns:po="urn:flottila:mapping:po"$/ { print "    xmlns:map=\"http://www.w3.org/2005/xpath-functions/map\"" }' "$module" ;;
    otherVersion) sed 's/<xsl:stylesheet version="3.0"/<xsl:stylesheet version="2.0"/' "$module" ;;
    oneLine)
      awk '/^<xsl:stylesheet / { tag = $0; open = 1; next }
           open { sub(/^ +/, ""); tag = tag " " $0; if (/>$/) { print tag; open = 0 }; next }
           { print }' "$module" ;;
  esac
}

anotherRootTagFailsTheBuild() {
  local c=anotherRootTagFailsTheBuild t way
  t=$(tree alpha beta)
  check "$c: witness: alpha, beta build" "$(run "$t" helper)" 0
  for way in extraNamespace otherVersion oneLine; do
    t=$(tree alpha beta)
    otherRootTag $way > "$t/po-runtime/beta.xsl"
    check "$c: beta.xsl $way: the module differs" "$(same "$fixtures/po-runtime/beta.xsl" "$t/po-runtime/beta.xsl")" differ
    check "$c: beta.xsl $way: exit" "$(failed "$(run "$t" helper)")" non-zero
    check "$c: beta.xsl $way: the message names the file" \
      "$(grep -q 'beta\.xsl' "$t/out/helper.log" && echo yes || echo no)" yes
  done
}

aNewFamilyNeedsOnlyItsOwnFiles
theMergeIgnoresListingOrder
aNameInTwoFamiliesFailsTheBuild
aFamilyFileNotCanonicalFailsTheBuild
anotherRootTagFailsTheBuild
exit $fails
