#!/usr/bin/env bash
# #111: the provisional bytes per unit b (ADR-0046 #111 amendment), measured on #111's deep filters.
#
#   bench/stack-units/measure-b.sh
#
# The compile mode is the engine's default, OPTIMISED: the public builder sets no other (CompileMode is engine-internal).
#
# Needs jq-core and query-core built (target/classes), e.g. ./mvnw -o -B -q -pl jq-core -am compile.
# Environment: JAVA_HOME (default /opt/temurin-25).
#
# Each cell is one fresh JVM with JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS and _JAVA_OPTIONS unset, so no agent. The case
# runs on the launcher's main thread, sized by -Xss: the process's first thread, so glibc's stack cache cannot hand it
# a larger stack an ended thread left. Proof per cell: a plain recursion to StackOverflowError on that thread first,
# printed as proofFrames; a cell whose proof is missing, or whose 1 MiB pair holds not 1.5 to 3.5 times its frames,
# is INVALID and stops the run. Then a binary search for the largest U_f at which
# the case completes on that thread (the next ends STACK), and b = ceil((S - reserve) / (U_f + D_f)).
# Tiers: -Xint; C1 only (-Xbatch -XX:TieredStopAtLevel=1); warm (the default JIT). C1 and warm run 30 warm-ups first.
# After the cells, the slope per tier and case between 512 KiB and 1 MiB: 512 KiB / (U_f(1 MiB) - U_f(512 KiB)).
# Output: a header of `# key: value` lines, one line per cell, one line per slope, then the largest of all.
set -euo pipefail
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS
cd "$(dirname "$0")/../.." || exit 2
JAVA_HOME=${JAVA_HOME:-/opt/temurin-25}
ROOT=$PWD
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/Probe.java" <<'EOF'
import dev.rail8.jq.Bindings;
import dev.rail8.jq.JqEngine;
import dev.rail8.jq.JqFilter;
import dev.rail8.jq.JqFilterException;
import dev.rail8.jq.JqSink;
import dev.rail8.jq.JqTermination;
import dev.rail8.jq.Limits;
import dev.rail8.jq.Workspace;
import dev.rail8.jq.value.JqValue;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** One cell. args: <thread bytes as given to -Xss> <case> [warm]. */
public class Probe {
  static int depth;

  static void recurse() {
    depth++;
    recurse();
  }

  static final String DEEP = "[".repeat(9_999) + "]".repeat(9_999);
  static final String HOLD = "def g: if . == 0 then " + "try(".repeat(100) + "." + ")".repeat(100) + " else (. - 1 | g) end; def f: if . == 0 then 0 else (. - 1 | f) | (1 | g) end; 9999 | f";

  public static void main(String[] args) {
    long thread = Long.parseLong(args[0]);
    String id = args[1];
    boolean warm = args.length > 2;
    // The proof of the thread's size, before any other work on it.
    try {
      recurse();
    } catch (StackOverflowError e) {
      // measured
    }
    int proof = depth;
    String filter;
    String json;
    String expected;
    switch (id) {
      case "collect" -> { filter = "def d: [.[]?|d]; d|length"; json = DEEP; expected = "[1]"; }
      case "reduce" -> { filter = "def r: reduce .[] as $x (0; .+($x|r)+1); r"; json = DEEP; expected = "[9998]"; }
      case "first-or" -> { filter = "def l: first(.[]?|l)//7; l"; json = DEEP; expected = "[7]"; }
      case "hold" -> { filter = HOLD; json = "null"; expected = "[0]"; }
      default -> throw new IllegalArgumentException(id);
    }
    String text = engine(Limits.cli().stackBytes()).compile(filter).explain();
    long callFree = -1;
    long b0 = -1;
    long reserve = -1;
    for (String line : text.split("\n")) {
      if (line.startsWith("stack units filter ")) {
        for (String f : line.split(" ")) {
          if (f.startsWith("callFreeDepth=")) callFree = Long.parseLong(f.substring(14));
          if (f.startsWith("bytesPerUnit=")) b0 = Long.parseLong(f.substring(13));
          if (f.startsWith("reserve=")) reserve = Long.parseLong(f.substring(8));
        }
      }
    }
    if (callFree < 0 || b0 <= 0 || reserve < 0) throw new IllegalStateException("no stack units line in the explain text");
    if (warm) {
      for (int i = 0; i < 30; i++) {
        ended(filter, json, 0);
        ended(filter, json, reserve + b0 * (callFree + 300));
      }
    }
    long lo = 0; // completes
    long hi = 40_000; // does not complete on a thread of at most 1 MiB
    if (!ended(filter, json, reserve + b0 * (callFree + lo)).equals(expected + ", ran out")) {
      System.out.println(id + " FAILS AT U_f 0: " + ended(filter, json, reserve));
      System.exit(1);
    }
    if (!ended(filter, json, reserve + b0 * (callFree + hi)).equals("[], termination STACK")) {
      System.out.println(id + " COMPLETES AT U_f " + hi);
      System.exit(1);
    }
    while (hi - lo > 1) {
      long mid = (lo + hi) / 2;
      String end = ended(filter, json, reserve + b0 * (callFree + mid));
      if (end.equals(expected + ", ran out")) lo = mid;
      else if (end.equals("[], termination STACK")) hi = mid;
      else throw new IllegalStateException(id + " at U_f " + mid + ": " + end);
    }
    long b = Math.ceilDiv(thread - reserve, lo + callFree);
    System.out.println("cell " + id + " thread=" + thread + " proofFrames=" + proof + " D_f=" + callFree + " maxU_f=" + lo + " b=" + b);
  }

  static JqEngine engine(long stackBytes) {
    Limits cli = Limits.cli();
    return JqEngine.builder()
        .limits(new Limits(cli.steps(), cli.deadline(), cli.nestingDepth(), cli.recursionDepth(), cli.bytesBuilt(), cli.bytesWritten(), cli.regexMatchTime(), stackBytes))
        .build();
  }

  static String ended(String filter, String json, long stackBytes) {
    JqEngine engine = engine(stackBytes);
    JqFilter compiled = engine.compile(filter);
    Workspace workspace = engine.newWorkspace();
    JqValue root = workspace.read(MemorySegment.ofArray(json.getBytes(StandardCharsets.US_ASCII))).root();
    List<JqValue> values = new ArrayList<>();
    JqSink sink = v -> {
      values.add(v);
      return true;
    };
    String end;
    try {
      end = compiled.run(root, Bindings.builder().build(), sink, workspace) ? "ran out" : "stopped";
    } catch (JqFilterException e) {
      end = "error " + e.value().toJson();
    } catch (JqTermination t) {
      end = "termination " + t.cause();
    }
    return values.stream().map(JqValue::toJson).toList() + ", " + end;
  }
}
EOF

CP=$ROOT/jq-core/target/classes:$ROOT/query-core/target/classes
"$JAVA_HOME/bin/javac" -d "$WORK" -cp "$CP" "$WORK/Probe.java"

echo "# commit: $(git rev-parse HEAD)"
echo "# tracked changes: $(git status --porcelain --untracked-files=no | wc -l)"
echo "# mode: OPTIMISED (the engine default)"
echo "# java: $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
echo "# glibc: $(ldd --version | head -1)"

declare -A maxu proof
largest=0
for size in 512 1024; do
  for tier in xint c1 warm; do
    case $tier in
      xint) flags=(-Xint); extra=() ;;
      c1) flags=(-Xbatch -XX:TieredStopAtLevel=1); extra=(warm) ;;
      warm) flags=(); extra=(warm) ;;
    esac
    for c in collect reduce first-or hold; do
      out=$("$JAVA_HOME/bin/java" "${flags[@]}" "-Xss${size}k" -cp "$WORK:$CP" Probe $((size * 1024)) "$c" "${extra[@]}" < /dev/null 2>&1 | tail -1 || true)
      case $out in
        "cell $c thread="*" proofFrames="*) ;;
        *) echo "INVALID $tier $size $c: $out"; exit 1 ;;
      esac
      echo "$tier $size $out"
      u=${out##*maxU_f=}
      u=${u%% *}
      b=${out##*b=}
      maxu[$tier.$c.$size]=$u
      p=${out##*proofFrames=}
      proof[$tier.$c.$size]=${p%% *}
      if [ "$b" -gt "$largest" ]; then largest=$b; fi
    done
  done
done
for tier in xint c1 warm; do
  for c in collect reduce first-or hold; do
    # The pair's proof: a thread of twice the size holds between 1.5 and 3.5 times the frames (guard pages make it
    # more than twice); a 512 KiB cell on a larger stack would not.
    small=${proof[$tier.$c.512]}
    large=${proof[$tier.$c.1024]}
    if [ $((2 * large)) -lt $((3 * small)) ] || [ $((2 * large)) -gt $((7 * small)) ]; then
      echo "INVALID $tier $c: proofFrames $small at 512 KiB against $large at 1 MiB"
      exit 1
    fi
    du=$((maxu[$tier.$c.1024] - maxu[$tier.$c.512]))
    slope=$(( (512 * 1024 + du - 1) / du ))
    echo "slope $tier $c $slope"
    if [ "$slope" -gt "$largest" ]; then largest=$slope; fi
  done
done
echo "largest $largest rounded-to-8 $(( (largest + 7) / 8 * 8 ))"
