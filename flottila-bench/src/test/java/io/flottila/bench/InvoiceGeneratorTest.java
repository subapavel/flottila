package io.flottila.bench;

import io.flottila.bench.InvoiceGenerator.Format;
import io.flottila.bench.InvoiceGenerator.Mix;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CORE-2's payloads: reproducible, varied, the target size, well-formed, and the same content in both formats. */
class InvoiceGeneratorTest {

    private static final long SEED = 42;

    @Test
    void theSameSeedAndIndexGiveTheSameBytesFromAFreshGenerator() {
        for (Mix mix : Mix.values()) {
            for (Format format : Format.values()) {
                byte[] first = new InvoiceGenerator(SEED, mix).document(7, format, 20_000);
                byte[] again = new InvoiceGenerator(SEED, mix).document(7, format, 20_000);
                assertArrayEquals(first, again, mix + " " + format);
            }
        }
    }

    @Test
    void anotherSeedGivesOtherDocuments() {
        assertFalse(Arrays.equals(new InvoiceGenerator(SEED, Mix.MIXED).document(0, Format.XML, 5_000),
                new InvoiceGenerator(SEED + 1, Mix.MIXED).document(0, Format.XML, 5_000)));
    }

    @Test
    void noTwoDocumentsAreAlikeAndEachHasItsOwnInvoiceId() throws Exception {
        for (Mix mix : Mix.values()) {
            InvoiceGenerator gen = new InvoiceGenerator(SEED, mix);
            Set<String> bodies = new HashSet<>();
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < 100; i++) {
                byte[] doc = gen.document(i, Format.XML, 4_000);
                bodies.add(new String(doc, StandardCharsets.US_ASCII));
                ids.add(text(xml(doc).getDocumentElement(), "Id"));
            }
            assertEquals(100, bodies.size(), mix + ": a repeated document");
            assertTrue(ids.size() >= 99, mix + ": invoice ids " + ids.size() + " of 100 distinct");
        }
    }

    @Test
    void aDocumentReachesTheTargetAndOvershootsByLessThanOneLineAndTheClosingElements() {
        InvoiceGenerator gen = new InvoiceGenerator(SEED, Mix.MIXED);
        for (Format format : Format.values()) {
            for (long target : new long[] {1, 100 << 10, 1 << 20}) {
                for (int i = 0; i < 5; i++) {
                    int size = gen.document(i, format, target).length;
                    assertTrue(size >= target, format + " " + target + ": " + size);
                    assertTrue(size < Math.max(target, 1024) + 512, format + " " + target + ": " + size);
                }
            }
        }
    }

    @Test
    void aTargetOutsideOneJavaArrayIsRefused() {
        InvoiceGenerator gen = new InvoiceGenerator(SEED, Mix.SIMILAR);
        assertThrows(IllegalArgumentException.class, () -> gen.document(0, Format.XML, 0));
        assertThrows(IllegalArgumentException.class, () -> gen.document(0, Format.XML, InvoiceGenerator.MAX_TARGET + 1));
    }

    @Test
    void theXmlIsTheDemoShapeWithLinesWhoseAmountsAddUpToTheTotal() throws Exception {
        Element invoice = xml(new InvoiceGenerator(SEED, Mix.MIXED).document(3, Format.XML, 50_000)).getDocumentElement();
        assertEquals("Invoice", invoice.getTagName());
        assertFalse(text(invoice, "Id").isEmpty());
        assertEquals(3, text(invoice, "Currency").length());
        NodeList lines = ((Element) invoice.getElementsByTagName("Lines").item(0)).getElementsByTagName("Line");
        assertTrue(lines.getLength() > 1, "lines: " + lines.getLength());
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < lines.getLength(); i++) {
            Element line = (Element) lines.item(i);
            BigDecimal qty = new BigDecimal(text(line, "Qty"));
            BigDecimal price = new BigDecimal(text(line, "Price"));
            BigDecimal amount = new BigDecimal(text(line, "Amount"));
            assertFalse(text(line, "Sku").isEmpty());
            assertEquals(0, qty.multiply(price).compareTo(amount), "line " + (i + 1));
            assertEquals(i + 1, Integer.parseInt(text(line, "No")));
            sum = sum.add(amount);
        }
        assertEquals(0, sum.compareTo(new BigDecimal(text(invoice, "Total"))));
    }

    @Test
    void theJsonCarriesTheSameInvoiceAsTheXmlOfTheSameIndex() throws Exception {
        InvoiceGenerator gen = new InvoiceGenerator(SEED, Mix.MIXED);
        for (int i = 0; i < 10; i++) {
            Element x = xml(gen.document(i, Format.XML, 30_000)).getDocumentElement();
            JsonObject j = new JsonObject(new String(gen.document(i, Format.JSON, 30_000), StandardCharsets.US_ASCII)).getJsonObject("Invoice");
            for (String field : new String[] {"Id", "IssueDate", "DueDate", "OrderRef", "Currency"}) {
                assertEquals(text(x, field), j.getString(field), "document " + i + " " + field);
            }
            Element partner = (Element) x.getElementsByTagName("Partner").item(0);
            for (String field : new String[] {"Id", "Name", "VatId", "Street", "City", "Country"}) {
                assertEquals(text(partner, field), j.getJsonObject("Partner").getString(field), "document " + i + " partner " + field);
            }
            NodeList xmlLines = x.getElementsByTagName("Line");
            JsonArray jsonLines = j.getJsonArray("Lines");
            int common = Math.min(xmlLines.getLength(), jsonLines.size());
            assertTrue(common > 1);
            for (int n = 0; n < common; n++) {
                Element xl = (Element) xmlLines.item(n);
                JsonObject jl = jsonLines.getJsonObject(n);
                assertEquals(text(xl, "Sku"), jl.getString("Sku"), "document " + i + " line " + (n + 1));
                assertEquals(text(xl, "Text"), jl.getString("Text"));
                assertEquals(Long.parseLong(text(xl, "Qty")), jl.getLong("Qty"));
                assertEquals(0, new BigDecimal(text(xl, "Price")).compareTo(new BigDecimal(jl.getValue("Price").toString())));
            }
        }
    }

    @Test
    void theMixedMixSpreadsOverManyPartnersAndTheSimilarMixHasOne() throws Exception {
        assertTrue(partners(Mix.MIXED).size() > 50, "mixed partners: " + partners(Mix.MIXED).size());
        assertEquals(1, partners(Mix.SIMILAR).size());
    }

    @Test
    void theSimilarMixStillVariesIdsDatesAndAmounts() throws Exception {
        InvoiceGenerator gen = new InvoiceGenerator(SEED, Mix.SIMILAR);
        Set<String> dates = new HashSet<>();
        Set<String> totals = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            Element invoice = xml(gen.document(i, Format.XML, 3_000)).getDocumentElement();
            dates.add(text(invoice, "IssueDate"));
            totals.add(text(invoice, "Total"));
        }
        assertTrue(dates.size() > 20, "issue dates: " + dates.size());
        assertTrue(totals.size() > 45, "totals: " + totals.size());
    }

    private static Set<String> partners(Mix mix) throws Exception {
        InvoiceGenerator gen = new InvoiceGenerator(SEED, mix);
        Set<String> names = new HashSet<>();
        for (int i = 0; i < 300; i++) {
            Element p = (Element) xml(gen.document(i, Format.XML, 1)).getDocumentElement().getElementsByTagName("Partner").item(0);
            names.add(text(p, "Id"));
        }
        return names;
    }

    private static Document xml(byte[] doc) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(doc));
    }

    /** The text of the first child element {@code name} directly under {@code parent}. */
    private static String text(Element parent, String name) {
        for (org.w3c.dom.Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(name)) return e.getTextContent();
        }
        throw new AssertionError("no <" + name + "> under <" + parent.getTagName() + ">");
    }
}
