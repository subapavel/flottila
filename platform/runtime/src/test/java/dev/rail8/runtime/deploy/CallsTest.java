package dev.rail8.runtime.deploy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code Calls.check(fresh, others, problems)}: one flow per node-local address — a flow already on the node keeps its address,
 * then the new flows in their order — and no cycle of calls through a new flow: a flow calls a flow when it calls the address
 * that flow serves. Each new flow's shortest cycle is one line starting at it, unless a line names that cycle already.
 * {@code Calls.unserved} lists the addresses called that no flow serves.
 */
class CallsTest {

    static Calls.Address direct(String value) {
        return new Calls.Address("direct", value);
    }

    static Calls.Node flow(String shown, String serves, String... calls) {
        return new Calls.Node(shown, shown + "-in", new Calls.Links(Optional.ofNullable(serves).map(CallsTest::direct),
                List.of(calls).stream().map(CallsTest::direct).sorted().toList()));
    }

    static List<String> check(List<Calls.Node> fresh, List<Calls.Node> others) {
        List<String> problems = new ArrayList<>();
        Calls.check(fresh, others, problems);
        return problems;
    }

    @Test
    void flowsOnDistinctAddressesCallingEachOtherOneWayPass() {
        assertEquals(List.of(), check(List.of(flow("orders", "/orders", "/billing"), flow("billing", "/billing", "/ledger")), List.of(flow("ledger/main", "/ledger"))));
    }

    @Test
    void aSecondFlowOnAnAddressIsRefusedNamingTheOneThatServesIt() {
        assertEquals(List.of("flow 'b' (endpoint 'b-in') serves direct /orders, which flow 'a' (endpoint 'a-in') serves: one flow per address on a node"),
                check(List.of(flow("a", "/orders"), flow("b", "/orders")), List.of()));
    }

    @Test
    void aFlowAlreadyOnTheNodeKeepsItsAddress() {
        assertEquals(List.of("flow 'a' (endpoint 'a-in') serves direct /orders, which flow 'shop/z' (endpoint 'shop/z-in') serves: one flow per address on a node"),
                check(List.of(flow("a", "/orders")), List.of(flow("shop/z", "/orders"))));
    }

    @Test
    void theSameValueUnderAnotherSchemeIsAnotherAddress() {
        Calls.Node sftp = new Calls.Node("a", "a-in", new Calls.Links(Optional.of(new Calls.Address("lane", "/orders")), List.of()));
        assertEquals(List.of(), check(List.of(sftp, flow("b", "/orders")), List.of()));
    }

    @Test
    void aFlowCallingTheAddressItServesIsACycle() {
        assertEquals(List.of("calls between flows would form a cycle: flow 'echo' -> direct /echo -> flow 'echo'"), check(List.of(flow("echo", "/echo", "/echo")), List.of()));
    }

    @Test
    void aCycleThroughAFlowOfAnotherPackageNamesThePath() {
        assertEquals(List.of("calls between flows would form a cycle: flow 'orders' -> direct /billing -> flow 'billing/main' -> direct /orders -> flow 'orders'"),
                check(List.of(flow("orders", "/orders", "/billing")), List.of(flow("billing/main", "/billing", "/orders"))));
    }

    @Test
    void aCycleOfTwoNewFlowsIsOneLineStartingAtTheFirst() {
        assertEquals(List.of("calls between flows would form a cycle: flow 'a' -> direct /b -> flow 'b' -> direct /a -> flow 'a'"),
                check(List.of(flow("a", "/a", "/b"), flow("b", "/b", "/a")), List.of()));
    }

    @Test
    void aCycleOfThreeFlowsNamesEveryStep() {
        assertEquals(List.of("calls between flows would form a cycle: flow 'a' -> direct /b -> flow 'x/b' -> direct /c -> flow 'y/c' -> direct /a -> flow 'a'"),
                check(List.of(flow("a", "/a", "/b")), List.of(flow("x/b", "/b", "/c"), flow("y/c", "/c", "/a"))));
    }

    @Test
    void theShortestCycleIsNamedAndOfTwoAsShortTheOneThroughTheFirstAddress() {
        // a -> /b -> b -> /a and a -> /c -> c -> /a: as short, /b comes first; a -> /d -> d -> /e -> e -> /a is longer
        List<Calls.Node> others = List.of(flow("x/e", "/e", "/a"), flow("x/d", "/d", "/e"), flow("x/c", "/c", "/a"), flow("x/b", "/b", "/a"));
        assertEquals(List.of("calls between flows would form a cycle: flow 'a' -> direct /b -> flow 'x/b' -> direct /a -> flow 'a'"),
                check(List.of(flow("a", "/a", "/d", "/c", "/b")), others));
    }

    @Test
    void aCycleIsNamedOnceAndAnotherThroughALaterNewFlowToo() {
        // a and b form one cycle, b and c a second; b's shortest is a and b's, named from a: the second is named from c
        assertEquals(List.of("calls between flows would form a cycle: flow 'a' -> direct /b -> flow 'b' -> direct /a -> flow 'a'",
                        "calls between flows would form a cycle: flow 'c' -> direct /b -> flow 'b' -> direct /c -> flow 'c'"),
                check(List.of(flow("a", "/a", "/b"), flow("b", "/b", "/a", "/c"), flow("c", "/c", "/b")), List.of()));
    }

    @Test
    void aSecondFlowOnAnAddressTakesNoCallsOfIt() {
        // b's serve is refused and leaves the graph: a call to /orders reaches a, and a's call to /x reaches nobody
        assertEquals(List.of("flow 'b' (endpoint 'b-in') serves direct /orders, which flow 'a' (endpoint 'a-in') serves: one flow per address on a node"),
                check(List.of(flow("a", "/orders", "/x"), flow("b", "/orders", "/orders")), List.of()));
    }

    @Test
    void aCycleOfFlowsAlreadyOnTheNodeAloneIsNotTheNewFlowsOne() {
        assertEquals(List.of(), check(List.of(flow("a", "/a")), List.of(flow("x/p", "/p", "/q"), flow("x/q", "/q", "/p"))));
    }

    @Test
    void unservedListsTheAddressesCalledThatNoFlowServes() {
        assertEquals(Set.of(direct("/billing"), direct("/tax")),
                Calls.unserved(List.of(flow("shop/orders", "/orders", "/billing", "/stock"), flow("shop/stock", "/stock", "/tax", "/orders"))));
    }
}
