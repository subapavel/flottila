#!/usr/bin/env bash
# The word check of query-core (ADR-0013, founding architecture section 14). query-core never mentions
# JSON or jq. Every tracked file under query-core/ (its path included) is split into words at camelCase
# boundaries, digits, "_", "." and any other punctuation, and the check fails on the words "jq" and "json"
# in any case, and on "non-sealed" under query/ir (ADR-0005). The project name "jqj" is a different word
# and passes. Files not yet added count too (untracked, not ignored), so the check works before a commit.
#
#   scripts/check-core-neutral.sh [<directory>]     default: query-core
#
# Exit 1 if a forbidden word is found; each hit is printed as file:line: word (line 0 is the path).
set -euo pipefail

cd "$(dirname "$0")/.."
dir=${1:-query-core}

if [ ! -d "$dir" ]; then
  echo "check-core-neutral: no directory $dir" >&2
  exit 2
fi

# Reads the file named by $1 (its path is also scanned, as line 0) and prints every forbidden word.
scan='
  sub words { my $t = shift; $t =~ s/([a-z])([A-Z])/$1 $2/g; $t =~ s/([A-Z]+)([A-Z][a-z])/$1 $2/g;
              return map { lc } grep { length } split /[^A-Za-z]+/, $t; }
  my $path = $ARGV[0];
  for my $w (words($path)) { print "$path:0: $w\n" if $w eq "jq" || $w eq "json"; }
  open(my $fh, "<", $path) or die "$path: $!";
  while (my $line = <$fh>) {
    for my $w (words($line)) { print "$path:$.: $w\n" if $w eq "jq" || $w eq "json"; }
    print "$path:$.: non-sealed\n" if $path =~ m{query/ir/} && $line =~ /non-sealed/i;
  }
'

status=0
while IFS= read -r -d '' file; do
  [ -f "$file" ] || continue
  # grep -I treats a file with a NUL byte as binary and does not match it; an empty file has no words.
  if [ -s "$file" ] && ! grep -qI '' "$file"; then
    continue
  fi
  hits=$(perl -e "$scan" "$file")
  if [ -n "$hits" ]; then
    printf '%s\n' "$hits"
    status=1
  fi
done < <(git ls-files -z --cached --others --exclude-standard -- "$dir")

if [ "$status" -eq 0 ]; then
  echo "check-core-neutral: no forbidden word under $dir"
fi
exit "$status"
