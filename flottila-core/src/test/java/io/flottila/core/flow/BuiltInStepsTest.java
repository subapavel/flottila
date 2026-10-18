package io.flottila.core.flow;

import io.flottila.api.Envelope;
import io.flottila.api.Expressions;
import io.flottila.api.Headers;
import io.flottila.api.Key;
import io.flottila.api.Pipeline;
import io.flottila.api.StepResources;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The builder's built-in route / filter / extractKey / validate, configured as the flow schema writes them, over a
 * registered expression language; here a tiny header language ({@code name=value} tests, {@code name} reads) so
 * core is tested without XML — the xpath language lives in flottila-step-xslt.
 */
class BuiltInStepsTest {

    /** {@code name=value} is a condition on a header; {@code name} is a header's value; {@code !} is a compile error. */
    static final Expressions HEADERS = new Expressions() {
        @Override public String language() { return "header"; }
        @Override public Predicate<Envelope> predicate(String expression, StepResources resources) {
            int eq = expression.indexOf('=');
            if (eq < 1) throw new IllegalArgumentException("not a header condition: " + expression);
            return e -> expression.substring(eq + 1).equals(e.headers().get(expression.substring(0, eq)));
        }
        @Override public Function<Envelope, Optional<String>> value(String expression, StepResources resources) {
            if (expression.startsWith("!")) throw new IllegalArgumentException("not a header name: " + expression);
            return e -> e.header(expression);
        }
    };

    final FakeRuntime rt = FakeRuntime.create();
    final FlowBuilder builder = new FlowBuilder(rt.runtime(), List.of(), List.of(HEADERS), Map.of());
    final Key<String> PARTNER = new Key<>("PARTNER");

    Pipeline build(Map<String, Object>... steps) {
        return builder.build(FlowDefinition.from(FlowBuilderTest.flow(List.of(steps))), FlowBuilderTest.NO_FILES);
    }

    Envelope invoice(String currency) {
        return rt.ingress("f", Headers.empty().with("currency", currency).with("id", "4711"), Buffer.buffer("<Invoice/>"));
    }

    @Test
    void routeFilterExtractKeyAndValidateFromTheFlowDocument() {
        Pipeline p = build(
                Map.of("type", "validate", "name", "validate", "language", "header", "required", "id=4711"),
                Map.of("type", "extractKey", "name", "extractKey", "language", "header", "key", "invoiceId", "xpath", "id"),
                Map.of("type", "route", "name", "route", "language", "header", "prop", "PARTNER",
                        "routes", List.of(Map.of("when", "currency=CZK", "set", "domestic")), "otherwise", "foreign"),
                Map.of("type", "filter", "name", "onlyDomestic", "language", "header", "when", "currency=CZK"));
        Envelope czk = p.apply(invoice("CZK"));
        assertEquals(Optional.of("4711"), czk.key("invoiceId"));
        assertEquals(Optional.of("domestic"), czk.get(PARTNER));
        assertEquals(4, czk.step(), "validate, extractKey, route, filter: four steps, all without a new body");
        Envelope eur = p.apply(invoice("EUR"));
        assertEquals(Optional.of("foreign"), eur.get(PARTNER));
        assertEquals(3, eur.step(), "filtered at step 4: handed back as the output of step 3");
        ValidationFailedException failed = assertThrows(ValidationFailedException.class, () -> p.apply(rt.ingress("f", Headers.empty(), Buffer.buffer("<x/>"))));
        assertEquals("validate", failed.step());
        assertEquals("id=4711", failed.expression());
        assertTrue(failed.getMessage().contains("validate") && failed.getMessage().contains("id=4711"), failed.getMessage());
    }

    @Test
    void extractKeyWithoutAValueKeepsNoKeyAndRouteWithoutOtherwiseFails() {
        Pipeline p = build(Map.of("type", "extractKey", "name", "k", "language", "header", "key", "orderNo", "xpath", "order"));
        assertEquals(Optional.empty(), p.apply(invoice("CZK")).key("orderNo"));
        Pipeline strict = build(Map.of("type", "route", "name", "route", "language", "header", "prop", "PARTNER",
                "routes", List.of(Map.of("when", "currency=CZK", "set", "domestic"))));
        assertEquals(Optional.of("domestic"), strict.apply(invoice("CZK")).get(PARTNER));
        assertTrue(assertThrows(IllegalStateException.class, () -> strict.apply(invoice("EUR"))).getMessage().contains("PARTNER"));
    }

    @Test
    void aBadConfigurationIsABuildErrorNamingTheStep() {
        String noLanguage = assertThrows(IllegalArgumentException.class, () -> build(Map.of("type", "filter", "name", "f", "when", "currency=CZK"))).getMessage();
        assertTrue(noLanguage.contains("xpath") && noLanguage.contains("'f'"), "the default language is xpath, and none is registered here: " + noLanguage);
        String badExpression = assertThrows(IllegalArgumentException.class, () -> build(Map.of("type", "filter", "name", "f", "language", "header", "when", "nonsense"))).getMessage();
        assertTrue(badExpression.contains("nonsense"), "compiled at build time: " + badExpression);
        String badValue = assertThrows(IllegalArgumentException.class, () -> build(Map.of("type", "extractKey", "name", "k", "language", "header", "key", "x", "xpath", "!bad"))).getMessage();
        assertTrue(badValue.contains("!bad"), badValue);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> build(Map.of("type", "filter", "name", "f", "language", "header"))).getMessage().contains("'when'"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> build(Map.of("type", "validate", "name", "v", "language", "header"))).getMessage().contains("'required'"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> build(Map.of("type", "extractKey", "name", "k", "language", "header", "xpath", "id"))).getMessage().contains("'key'"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> build(Map.of("type", "route", "name", "r", "language", "header", "routes", List.of()))).getMessage().contains("'prop'"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> build(Map.of("type", "route", "name", "r", "language", "header", "prop", "P", "routes", List.of()))).getMessage().contains("'routes'"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> build(Map.of("type", "route", "name", "r", "language", "header", "prop", "P", "routes", "all"))).getMessage().contains("'routes'"));
        String badBranch = assertThrows(IllegalArgumentException.class, () -> build(Map.of("type", "route", "name", "r", "language", "header", "prop", "P",
                "routes", List.of(Map.of("when", "currency=CZK", "set", "d"), Map.of("when", "currency=EUR"))))).getMessage();
        assertTrue(badBranch.contains("routes[1]"), badBranch);
        String badSet = assertThrows(IllegalArgumentException.class, () -> build(Map.of("type", "route", "name", "r", "language", "header", "prop", "P",
                "routes", List.of(Map.of("when", "currency=CZK", "set", 1))))).getMessage();
        assertTrue(badSet.contains("routes[0]") && badSet.contains("'set'"), badSet);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> build(Map.of("type", "route", "name", "r", "language", "header", "prop", "P",
                "routes", List.of("currency=CZK")))).getMessage().contains("routes[0]"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new FlowBuilder(rt.runtime(), List.of(), List.of(HEADERS, HEADERS), Map.of())).getMessage().contains("'header'"));
    }
}
