package dev.rail8.runtime.deploy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Calls between the flows of one node, as {@code Component.internalAddress} answers for their endpoints: a flow serves the
 * node-local address of its in endpoint and calls those of the out endpoints its steps send to. A flow calls a flow when it
 * calls the address that flow serves. Nothing is asked here: the deploy asks the components and hands over the answers.
 */
final class Calls {

    private Calls() {}

    /** A node-local address: the scheme of the endpoint that names it and its component's answer. Shown {@code direct /orders}. */
    record Address(String scheme, String value) implements Comparable<Address> {
        private static final Comparator<Address> ORDER = Comparator.comparing(Address::scheme).thenComparing(Address::value);

        @Override
        public int compareTo(Address other) {
            return ORDER.compare(this, other);
        }

        @Override
        public String toString() {
            return scheme + " " + value;
        }
    }

    /** The address a flow serves, if any, and those it calls, sorted, each once. */
    record Links(Optional<Address> serves, List<Address> calls) {
        /** A flow that serves and calls no node-local address. */
        static final Links NONE = new Links(Optional.empty(), List.of());

        Links {
            calls = List.copyOf(new TreeSet<>(calls));
        }
    }

    /** A flow as the lines name it — its local id in the package checked, {@code <package>/<flow>} in another — with its in endpoint. */
    record Node(String shown, String endpoint, Links links) {}

    /**
     * Adds a line to {@code problems} for each flow of {@code fresh}, the flows a deploy adds, that serves an address another
     * flow serves — the flows of {@code others}, already on the node, keep theirs, then each address goes to the first of
     * {@code fresh} in its order; a flow refused it serves nothing — and for each cycle of calls through a flow of
     * {@code fresh}, a self call included: of each one in order, the shortest cycle back to it, the one through the first
     * address in {@link Address} order of two as short, unless a line names that cycle already. A cycle of {@code others}
     * alone is not named: the node refused it when it came.
     */
    static void check(List<Node> fresh, List<Node> others, List<String> problems) {
        Map<Address, Node> holders = new HashMap<>();
        for (Node n : others) n.links().serves().ifPresent(a -> holders.putIfAbsent(a, n));
        for (Node n : fresh) {
            Optional<Address> serves = n.links().serves();
            if (serves.isEmpty()) continue;
            Node holder = holders.putIfAbsent(serves.get(), n);
            if (holder != null) {
                problems.add(named(n) + " (endpoint '" + n.endpoint() + "') serves " + serves.get() + ", which " + named(holder) + " (endpoint '"
                        + holder.endpoint() + "') serves: one flow per address on a node");
            }
        }
        List<Set<String>> reported = new ArrayList<>();   // each cycle named, as its calls: a rotation of it is the same set
        for (Node start : fresh) {
            List<Node> path = cycle(start, holders);
            if (path.isEmpty()) continue;
            Set<String> calls = new HashSet<>();
            StringBuilder line = new StringBuilder("calls between flows would form a cycle: ").append(named(start));
            for (int i = 0; i < path.size(); i++) {
                Node next = i + 1 < path.size() ? path.get(i + 1) : start;
                Address a = next.links().serves().orElseThrow();
                calls.add(path.get(i).shown() + " " + a);
                line.append(" -> ").append(a).append(" -> ").append(named(next));
            }
            if (reported.contains(calls)) continue;
            reported.add(calls);
            problems.add(line.toString());
        }
    }

    /** The addresses a flow of {@code all} calls that none of them serves, sorted. */
    static SortedSet<Address> unserved(Collection<Node> all) {
        Set<Address> served = new HashSet<>();
        all.forEach(n -> n.links().serves().ifPresent(served::add));
        SortedSet<Address> unserved = new TreeSet<>();
        all.forEach(n -> n.links().calls().stream().filter(a -> !served.contains(a)).forEach(unserved::add));
        return unserved;
    }

    /**
     * The flows of the shortest cycle of calls from {@code start} back to it, {@code start} first — a breadth-first search that
     * follows each flow's calls in address order — or none.
     */
    private static List<Node> cycle(Node start, Map<Address, Node> holders) {
        Map<Node, Node> before = new HashMap<>();   // each flow reached, to the flow it was reached from
        Deque<Node> queue = new ArrayDeque<>(List.of(start));
        before.put(start, start);
        while (!queue.isEmpty()) {
            Node n = queue.removeFirst();
            for (Address a : n.links().calls()) {
                Node callee = holders.get(a);
                if (start.equals(callee)) {
                    List<Node> path = new ArrayList<>();
                    for (Node at = n; !at.equals(start); at = before.get(at)) path.addFirst(at);
                    path.addFirst(start);
                    return path;
                }
                if (callee != null && before.putIfAbsent(callee, n) == null) queue.addLast(callee);
            }
        }
        return List.of();
    }

    private static String named(Node n) {
        return "flow '" + n.shown() + "'";
    }
}
