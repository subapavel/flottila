package dev.rail8.bench;

import dev.rail8.mapping.embed.BundleReference;
import dev.rail8.mapping.embed.Mapping;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.Serializer;
import net.sf.saxon.s9api.XsltTransformer;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The node bench's {@code mapping} step: {@code --step mapping} deploys a flow whose mapping, run on the ~100 KB invoice,
 * gives the partner the invoice the xslt step's stylesheet gives: the same number, currency, total and items, the numbers
 * compared as decimals.
 */
class NodeBenchMappingTest {

    @Test
    void theMappingGivesWhatTheStylesheetGives() throws Exception {
        NodeBenchMain.Options o = NodeBenchMain.options(new String[] {"--scenario", "worker", "--step", "mapping"});
        assertEquals(NodeBenchMain.Step.MAPPING, o.step());
        Map<String, byte[]> files = unzip(NodeBenchMain.benchPackage(o, 1));
        String flow = new String(files.get("flows/bench.yaml"), StandardCharsets.UTF_8);
        assertTrue(flow.contains("flows/transform.mapping.json"), "the flow runs the mapping step: " + flow);

        Mapping mapping = Mapping.compile(files.get("flows/transform.mapping.json"), reference -> switch (reference) {
            case BundleReference.Relative(String path) -> files.get(Path.of("flows").resolve(path).normalize().toString());
            case BundleReference.Packaged packaged -> throw new IllegalArgumentException(packaged.spelling());
        });
        byte[] invoice = NodeBenchMain.invoice(NodeBenchMain.XSLT_BODY_BYTES).getBytes();
        Invoice mapped = Invoice.of(mapping.run(invoice, NodeBenchMain.MAX_BODY_BYTES, OffsetDateTime.parse("2026-11-19T00:00:00Z")));
        Invoice transformed = Invoice.of(xslt(invoice));

        assertEquals(transformed, mapped);
        assertTrue(mapped.items().size() > 1_000, "one item per line of the ~100 KB invoice: " + mapped.items().size());
    }

    /** The partner's invoice: its attributes, its total and its items' SKUs and amounts. */
    record Invoice(String number, String currency, BigDecimal total, List<String> items) {
        static Invoice of(byte[] xml) throws Exception {
            Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(xml)).getDocumentElement();
            assertEquals("PartnerInvoice", root.getTagName());
            NodeList item = root.getElementsByTagName("Item");
            List<String> items = new ArrayList<>();
            for (int i = 0; i < item.getLength(); i++) {
                Element e = (Element) item.item(i);
                items.add(e.getAttribute("sku") + "=" + new BigDecimal(e.getAttribute("amount")).stripTrailingZeros().toPlainString());
            }
            String total = root.getElementsByTagName("Total").item(0).getTextContent();
            return new Invoice(root.getAttribute("number"), root.getAttribute("currency"), new BigDecimal(total).stripTrailingZeros(), items);
        }
    }

    /** {@code invoice} through the xslt step's stylesheet. */
    static byte[] xslt(byte[] invoice) throws Exception {
        Processor saxon = new Processor(false);
        XsltTransformer t = saxon.newXsltCompiler().compile(new StreamSource(new StringReader(NodeBenchMain.STYLESHEET))).load();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        t.setSource(new StreamSource(new ByteArrayInputStream(invoice)));
        Serializer s = saxon.newSerializer(out);
        t.setDestination(s);
        t.transform();
        return out.toByteArray();
    }

    static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) files.put(e.getName(), in.readAllBytes());
        }
        return files;
    }
}
