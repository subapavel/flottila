package io.flottila.runtime;

import io.flottila.api.Component;
import io.flottila.api.Expressions;
import io.flottila.api.StepFactory;
import io.flottila.runtime.node.ComponentContext;
import io.flottila.steps.validate.XsdValidate;
import io.flottila.steps.xslt.SaxonXml;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P2-03a (D-079, F-16): {@code Main.shipped()} is the one place that names what a node ships — the node ({@code Main.start}) and
 * the CLI both take it from there. It ships what {@code Main} wired before it existed: the http and sftp components (no mock: a
 * node never names one), the xslt steps of {@link SaxonXml} and the xsd step of {@link XsdValidate}, in that order, and the
 * xpath language.
 */
class MainShippedTest {

    @Test
    void shippedIsTheHttpAndSftpComponentsTheXsltAndXsdStepsAndXpath() {
        Main.Shipped shipped = Main.shipped();
        FakeRuntime rt = FakeRuntime.create();
        Vertx vertx = Vertx.vertx();
        try {
            List<Component> components = shipped.components().apply(new ComponentContext(vertx, rt.blobs(), rt.ids(), rt.timer(), rt.clock()));

            assertEquals(List.of("http", "sftp"), components.stream().map(Component::scheme).toList(), "the components a node serves");
        } finally {
            vertx.close().await();
        }
        List<String> wired = Stream.concat(new SaxonXml().steps().stream(), new XsdValidate().steps().stream()).map(StepFactory::type).toList();
        assertEquals(wired, shipped.steps().stream().map(StepFactory::type).toList(), "the step types Main wired: SaxonXml's, then XsdValidate's");
        assertEquals(new SaxonXml().languages().stream().map(Expressions::language).toList(),
                shipped.languages().stream().map(Expressions::language).toList(), "SaxonXml's languages");
        assertEquals(List.of("xpath"), shipped.languages().stream().map(Expressions::language).toList(), "xpath");
    }
}
