#!/usr/bin/env bash
# Script test for the refusals of scripts/function-families.java (#69) that
# function-families-test.sh does not reach: a family file that is no JSON, or no object of objects;
# a helper module without core.xsl, a core.xsl without a root start tag, a module that is no XML, a
# declaration in two modules; a missing input, a family given twice, too few arguments and an
# unknown mode. Each exits non-zero and names the file or the fault, without a stack trace where
# the case checks it. A null member, which the canonical form allows, merges as it is.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
tool=$here/../function-families.java
java=${JAVA_HOME:+$JAVA_HOME/bin/}java
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0
check() { if [ "$2" != "$3" ]; then echo "FAIL $1: expected '$3' got '$2'"; fails=1; else echo "ok   $1"; fi; }
failed() { if [ "$1" = 0 ]; then echo 0; else echo non-zero; fi; }

# catalogue <case> <text>: the generator over one family file delta.json holding <text>; prints the exit
catalogue() {
  mkdir -p "$tmp/$1"
  printf '%s' "$2" > "$tmp/$1/delta.json"
  "$java" "$tool" catalogue "$tmp/$1/out.json" "$tmp/$1/delta.json" > "$tmp/$1/log" 2>&1
  echo $?
}
# refused <case> <text> <message part>: the file is refused, and the message names it and the fault
refused() {
  check "$1: exit" "$(failed "$(catalogue "$1" "$2")")" non-zero
  check "$1: the message names the file" "$(grep -c 'delta\.json' "$tmp/$1/log")" 1
  check "$1: the message names the fault" "$(grep -cF "$3" "$tmp/$1/log")" 1
}

aFamilyFileThatIsNoJsonIsRefused() {
  refused noColon $'{\n  "a" "b"\n}\n' "':' expected"
  refused noComma $'{\n  "a": "b"\n  "c": "d"\n}\n' "',' or '}' expected"
  refused noMemberName $'{\n  a: "b"\n}\n' "a member name expected"
  refused noArrayComma $'{\n  "a": [\n    "b"\n    "c"\n  ]\n}\n' "',' or ']' expected"
  refused controlCharacter $'{\n  "a": "b\tc"\n}\n' "a string expected"
  refused textAfter $'{\n  "a": "b"\n}\nx\n' "the end of the document expected"
  refused number $'{\n  "a": 1\n}\n' "a value expected"
}

aFamilyFileThatIsNoObjectOfObjectsIsRefused() {
  refused array $'[\n  "a"\n]\n' "is no JSON object"
  refused stringEntry $'{\n  "a": "b"\n}\n' "the entry a is no JSON object"
  # an empty object that is no default is left out of the canonical form
  refused emptyObject $'{\n  "a": {\n    "inputs": {}\n  }\n}\n' "not in canonical form"
  # the canonical form holds no empty array, a default's included, however it is spaced
  refused emptyArrayDefault $'{\n  "a": {\n    "default": []\n  }\n}\n' "not in canonical form"
  refused spacedEmptyArrayDefault $'{\n  "a": {\n    "default": [\n\n    ]\n  }\n}\n' "not in canonical form"
}

aNullMemberMergesAsItIs() {
  local c=aNullMemberMergesAsItIs
  check "$c: exit" "$(catalogue "$c" $'{\n  "a": {\n    "b": null\n  }\n}\n')" 0
  check "$c: the merge" "$(cat "$tmp/$c/out.json")" $'{\n  "a": {\n    "b": null,\n    "family": "delta"\n  }\n}'
}

aHelperModuleWithoutCoreIsRefused() {
  local c=aHelperModuleWithoutCoreIsRefused
  "$java" "$tool" helper "$tmp/po-runtime.xsl" "$here/function-families/po-runtime/alpha.xsl" > "$tmp/helper.log" 2>&1
  check "$c: exit" "$(failed $?)" non-zero
  check "$c: the message" "$(grep -c 'no core\.xsl among the inputs' "$tmp/helper.log")" 1
  check "$c: no output" "$(test -e "$tmp/po-runtime.xsl" && echo written || echo none)" none
}

anUnknownModeIsRefused() {
  local c=anUnknownModeIsRefused
  "$java" "$tool" catalog "$tmp/x.json" "$here/function-families/catalogue" > "$tmp/mode.log" 2>&1
  check "$c: exit" "$(failed $?)" non-zero
  check "$c: the message" "$(grep -c 'usage: java scripts/function-families.java' "$tmp/mode.log")" 1
}

# generate <case> <mode> <output file name> <input>...: the generator into $tmp/<case>, its stdout and
# stderr into $tmp/<case>/log; prints the exit code
generate() {
  local c=$1 mode=$2 out=$3
  shift 3
  mkdir -p "$tmp/$c"
  "$java" "$tool" "$mode" "$tmp/$c/$out" "$@" > "$tmp/$c/log" 2>&1
  echo $?
}
written() { if [ -e "$1" ]; then echo written; else echo none; fi; }
# says <case> <fixed string>: how many lines of the case's log hold the string
says() { grep -cF -- "$2" "$tmp/$1/log"; }

# a moved or misspelt directory would otherwise ship an empty catalogue with exit 0 (#69 review)
aMissingInputIsRefused() {
  local c=aMissingInputIsRefused m
  for m in catalogue helper index; do
    check "$c: $m: exit" "$(failed "$(generate "$c-$m" $m out "$tmp/nowhere/src")")" non-zero
    check "$c: $m: the message names the input" "$(says "$c-$m" "$tmp/nowhere/src")" 1
    check "$c: $m: no output" "$(written "$tmp/$c-$m/out")" none
  done
}

tooFewArgumentsPrintTheUsage() {
  local c=tooFewArgumentsPrintTheUsage
  mkdir -p "$tmp/$c-none" "$tmp/$c-one"
  "$java" "$tool" > "$tmp/$c-none/log" 2>&1
  check "$c: no argument: exit" "$(failed $?)" non-zero
  "$java" "$tool" catalogue > "$tmp/$c-one/log" 2>&1
  check "$c: one argument: exit" "$(failed $?)" non-zero
  for n in none one; do
    check "$c: $n: the usage" "$(says "$c-$n" 'usage: java scripts/function-families.java')" 1
    check "$c: $n: no stack trace" "$(says "$c-$n" 'Exception')" 0
  done
}

aCoreWithoutRootStartTagIsRefused() {
  local c=aCoreWithoutRootStartTagIsRefused
  mkdir -p "$tmp/$c-in"
  printf '<?xml version="1.0" encoding="UTF-8"?>\n<!-- no root start tag -->\n' > "$tmp/$c-in/core.xsl"
  cp "$here/function-families/po-runtime/alpha.xsl" "$tmp/$c-in/"
  check "$c: exit" "$(failed "$(generate $c helper po-runtime.xsl "$tmp/$c-in")")" non-zero
  check "$c: the message names core.xsl" "$(says $c "$tmp/$c-in/core.xsl")" 1
  check "$c: no stack trace" "$(says $c 'Exception')" 0
  check "$c: no output" "$(written "$tmp/$c/po-runtime.xsl")" none
}

# helper: one top-level declaration, by element and name, in two modules; the function names of the
# runtime's family files are refused by FunctionFamilies.merge (FunctionFamiliesTest), not here
aDeclarationInTwoModulesIsRefused() {
  local c=aDeclarationInTwoModulesIsRefused d
  for d in apple SUPPRESS; do
    mkdir -p "$tmp/$c-$d-in"
    cp "$here/function-families/po-runtime/"{core,alpha}.xsl "$tmp/$c-$d-in/"
    case $d in
      apple) sed 's/po:kiwiB/po:apple/' "$here/function-families/po-runtime/beta.xsl" > "$tmp/$c-$d-in/delta.xsl" ;;
      SUPPRESS) sed 's|  <xsl:function name="po:cherry"|  <xsl:variable name="po:SUPPRESS" select="1"/>\n&|' \
        "$here/function-families/po-runtime/gamma.xsl" > "$tmp/$c-$d-in/delta.xsl" ;;
    esac
    check "$c: po:$d: exit" "$(failed "$(generate "$c-$d" helper po-runtime.xsl "$tmp/$c-$d-in")")" non-zero
    check "$c: po:$d: the message names it" "$(says "$c-$d" "po:$d")" 1
    check "$c: po:$d: the message names delta.xsl" "$(says "$c-$d" delta.xsl)" 1
    check "$c: po:$d: no output" "$(written "$tmp/$c-$d/po-runtime.xsl")" none
  done
}

aModuleThatIsNoXmlIsRefused() {
  local c=aModuleThatIsNoXmlIsRefused
  mkdir -p "$tmp/$c-in"
  cp "$here/function-families/po-runtime/"{core,alpha}.xsl "$tmp/$c-in/"
  sed 's|</xsl:function>|</xsl:functio>|' "$here/function-families/po-runtime/beta.xsl" > "$tmp/$c-in/beta.xsl"
  check "$c: exit" "$(failed "$(generate $c helper po-runtime.xsl "$tmp/$c-in")")" non-zero
  check "$c: the refusal names beta.xsl" \
    "$(grep -F 'function-families: ' "$tmp/$c/log" | grep -c "$tmp/$c-in/beta\.xsl")" 1
  check "$c: no stack trace" "$(says $c 'Exception')" 0
}

# every mode: one family's file given twice, from two directories, is refused, as one name in two
# family files is
aFamilyGivenTwiceIsRefused() {
  local c=aFamilyGivenTwiceIsRefused m file
  for m in catalogue helper index; do
    case $m in
      catalogue) file=catalogue/alpha.json ;;
      helper) file=po-runtime/alpha.xsl ;;
      index) file=runtime/AlphaFamily.java ;;
    esac
    mkdir -p "$tmp/$c-$m-a" "$tmp/$c-$m-b"
    cp "$here/function-families/$file" "$tmp/$c-$m-a/"
    cp "$here/function-families/$file" "$tmp/$c-$m-b/"
    cp "$here/function-families/po-runtime/core.xsl" "$tmp/$c-$m-a/"
    check "$c: $m: exit" "$(failed "$(generate "$c-$m" $m out "$tmp/$c-$m-a" "$tmp/$c-$m-b")")" non-zero
    check "$c: $m: the message names both files" \
      "$(says "$c-$m" "$tmp/$c-$m-a/${file#*/}") $(says "$c-$m" "$tmp/$c-$m-b/${file#*/}")" "1 1"
    check "$c: $m: no output" "$(written "$tmp/$c-$m/out")" none
  done
}

aFamilyFileThatIsNoJsonIsRefused
aFamilyFileThatIsNoObjectOfObjectsIsRefused
aNullMemberMergesAsItIs
aHelperModuleWithoutCoreIsRefused
anUnknownModeIsRefused
aMissingInputIsRefused
tooFewArgumentsPrintTheUsage
aCoreWithoutRootStartTagIsRefused
aDeclarationInTwoModulesIsRefused
aModuleThatIsNoXmlIsRefused
aFamilyGivenTwiceIsRefused
exit $fails
