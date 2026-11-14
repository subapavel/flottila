package io.flottila.bench;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * CORE-2 payloads: invoices in the demo's XML shape ({@code packages/demo/fixtures/sample-invoice.xml}: {@code Invoice},
 * {@code Id}, {@code Currency}, {@code Lines/Line} with {@code Sku}, {@code Qty}, {@code Price}), extended by issue and due
 * date, partner, line text, unit, amount and total; or the same content as compact JSON. Seeded and reproducible: document
 * {@code index} of a generator is the same bytes on every call and every run, and differs from every other index (own
 * invoice id, dates, partner pick, lines, quantities, prices). Lines are appended until the document reaches the target
 * size, so a document overshoots the target by at most one line and the closing elements.
 * <p>{@link Mix#MIXED}: {@value #PARTNERS} partners, each with its own catalog, address and currency. {@link Mix#SIMILAR}: one
 * partner's template — every document from the same partner and catalog, only the variable fields differ. All text is ASCII
 * from fixed word lists, never in need of escaping in either format.
 *
 * @since 0.1
 */
final class InvoiceGenerator {

    enum Mix { MIXED, SIMILAR }

    enum Format { XML, JSON }

    static final int PARTNERS = 200;

    /** The largest target: a document is one Java array. */
    static final long MAX_TARGET = Integer.MAX_VALUE - (1 << 16);

    private static final int SLACK = 4096;

    private static final String[] NAME_A = {"Nord", "Agro", "Stav", "Tech", "Elektro", "Metal", "Trans", "Bio", "Kovo", "Data",
        "Auto", "Chem", "Pharma", "Textil", "Papir", "Sklo", "Drevo", "Plast", "Energo", "Logi"};
    private static final String[] NAME_B = {"servis", "mont", "trade", "systems", "group", "invest", "market", "line", "plus",
        "centrum", "union", "partner", "works", "point", "net"};
    private static final String[] LEGAL = {"s.r.o.", "a.s.", "GmbH", "AG", "Sp. z o.o.", "Kft.", "B.V.", "Ltd."};
    private static final String[] STREET = {"Hlavni", "Nadrazni", "Prumyslova", "Skolni", "Lipova", "Polni", "Dlouha", "Kratka",
        "Hauptstrasse", "Bahnhofstrasse", "Industrieweg", "Marktplatz"};
    private static final String[] CITY = {"Praha", "Brno", "Ostrava", "Plzen", "Liberec", "Olomouc", "Wien", "Graz", "Muenchen",
        "Dresden", "Bratislava", "Krakow", "Wroclaw", "Budapest"};
    private static final String[] COUNTRY = {"CZ", "CZ", "CZ", "SK", "DE", "AT", "PL", "HU"};
    private static final String[] CURRENCY = {"CZK", "CZK", "EUR", "EUR", "PLN", "HUF"};
    private static final String[] ADJ = {"Steel", "Brass", "Plastic", "Stainless", "Galvanized", "Copper", "Aluminium", "Rubber",
        "Ceramic", "Oak", "Reinforced", "Flexible", "Heavy-duty", "Compact", "Industrial"};
    private static final String[] NOUN = {"bolt", "nut", "washer", "bracket", "hinge", "valve", "pipe", "fitting", "cable", "panel",
        "bearing", "gasket", "clamp", "screw", "flange", "hose", "filter", "switch", "relay", "sensor"};
    private static final String[] SPEC = {"M6x20", "M8x40", "M10x60", "DN15", "DN25", "DN50", "3x1.5mm2", "5x2.5mm2", "200mm",
        "500mm", "1000mm", "IP65", "24V", "230V", "class A", "grade 8.8"};
    private static final String[] UNIT = {"PCE", "PCE", "PCE", "KGM", "MTR", "SET", "BOX"};
    private static final LocalDate EPOCH = LocalDate.of(2026, 1, 1);

    private record Item(String sku, String text, String unit, long cents) {}

    private record Partner(String id, String name, String vat, String street, String city, String country, String currency,
                           Item[] catalog) {}

    private final long seed;
    private final Partner[] partners;

    InvoiceGenerator(long seed, Mix mix) {
        this.seed = seed;
        SplittableRandom r = new SplittableRandom(seed);
        this.partners = new Partner[mix == Mix.MIXED ? PARTNERS : 1];
        for (int p = 0; p < partners.length; p++) partners[p] = partner(r, p);
    }

    /** Document {@code index}, at least {@code targetBytes} long; the same bytes for the same seed, mix, index, format and target. */
    byte[] document(int index, Format format, long targetBytes) {
        SplittableRandom r = new SplittableRandom(seed * 0x9E3779B97F4A7C15L + index);
        Partner p = partners[r.nextInt(partners.length)];
        String id = "INV-2026-" + String.format("%07d", r.nextInt(10_000_000));
        LocalDate issued = EPOCH.plusDays(r.nextInt(365));
        LocalDate due = issued.plusDays(new int[] {14, 30, 60}[r.nextInt(3)]);
        String order = "PO-" + r.nextInt(100_000, 1_000_000);
        if (targetBytes < 1 || targetBytes > MAX_TARGET) throw new IllegalArgumentException("target bytes 1.." + MAX_TARGET + ": " + targetBytes);
        Ascii out = new Ascii((int) targetBytes + SLACK);
        boolean xml = format == Format.XML;
        if (xml) {
            out.add("<Invoice>\n  <Id>").add(id).add("</Id>\n  <IssueDate>").add(issued.toString()).add("</IssueDate>\n  <DueDate>")
                    .add(due.toString()).add("</DueDate>\n  <OrderRef>").add(order).add("</OrderRef>\n  <Partner><Id>").add(p.id)
                    .add("</Id><Name>").add(p.name).add("</Name><VatId>").add(p.vat).add("</VatId><Street>").add(p.street)
                    .add("</Street><City>").add(p.city).add("</City><Country>").add(p.country).add("</Country></Partner>\n  <Currency>")
                    .add(p.currency).add("</Currency>\n  <Lines>\n");
        } else {
            out.add("{\"Invoice\":{\"Id\":\"").add(id).add("\",\"IssueDate\":\"").add(issued.toString()).add("\",\"DueDate\":\"")
                    .add(due.toString()).add("\",\"OrderRef\":\"").add(order).add("\",\"Partner\":{\"Id\":\"").add(p.id)
                    .add("\",\"Name\":\"").add(p.name).add("\",\"VatId\":\"").add(p.vat).add("\",\"Street\":\"").add(p.street)
                    .add("\",\"City\":\"").add(p.city).add("\",\"Country\":\"").add(p.country).add("\"},\"Currency\":\"")
                    .add(p.currency).add("\",\"Lines\":[");
        }
        long total = 0;
        int no = 0;
        do {
            Item item = p.catalog[r.nextInt(p.catalog.length)];
            long qty = 1 + r.nextInt(r.nextInt(10) == 0 ? 1000 : 20);
            long price = item.cents + item.cents * (r.nextInt(11) - 5) / 100;   // the partner's price ±5 %
            long amount = qty * price;
            total += amount;
            no++;
            if (xml) {
                out.add("    <Line><No>").num(no).add("</No><Sku>").add(item.sku).add("</Sku><Text>").add(item.text).add("</Text><Qty>")
                        .num(qty).add("</Qty><Unit>").add(item.unit).add("</Unit><Price>").cents(price).add("</Price><Amount>")
                        .cents(amount).add("</Amount></Line>\n");
            } else {
                out.add(no == 1 ? "{\"No\":" : ",{\"No\":").num(no).add(",\"Sku\":\"").add(item.sku).add("\",\"Text\":\"").add(item.text)
                        .add("\",\"Qty\":").num(qty).add(",\"Unit\":\"").add(item.unit).add("\",\"Price\":").cents(price)
                        .add(",\"Amount\":").cents(amount).add("}");
            }
        } while (out.size() < targetBytes);
        if (xml) out.add("  </Lines>\n  <Total>").cents(total).add("</Total>\n</Invoice>\n");
        else out.add("],\"Total\":").cents(total).add("}}");
        return out.bytes();
    }

    private static Partner partner(SplittableRandom r, int index) {
        String country = pick(r, COUNTRY);
        String name = pick(r, NAME_A) + pick(r, NAME_B) + " " + pick(r, LEGAL);
        Item[] catalog = new Item[r.nextInt(50, 401)];
        String prefix = name.substring(0, 2).toUpperCase() + (index % 10);
        for (int i = 0; i < catalog.length; i++) {
            catalog[i] = new Item(prefix + "-" + r.nextInt(1000, 100_000), pick(r, ADJ) + " " + pick(r, NOUN) + " " + pick(r, SPEC),
                    pick(r, UNIT), r.nextLong(50, 2_000_000));
        }
        return new Partner(String.format("P-%04d", index), name, country + r.nextInt(10_000_000, 100_000_000),
                pick(r, STREET) + " " + r.nextInt(1, 300), pick(r, CITY), country, pick(r, CURRENCY), catalog);
    }

    private static String pick(SplittableRandom r, String[] words) {
        return words[r.nextInt(words.length)];
    }

    /**
     * An ASCII byte buffer sized once: the header, one line and the closing elements are each far below {@link #SLACK}, and
     * lines stop once the target is reached, so a document never outgrows {@code target + SLACK}.
     */
    private static final class Ascii {
        private final byte[] buf;
        private int len;

        Ascii(int capacity) {
            buf = new byte[capacity];
        }

        Ascii add(String s) {
            int n = s.length();
            s.getBytes(0, n, buf, len);   // ASCII only (the word lists): the deprecated low-byte copy is exact and allocation-free
            len += n;
            return this;
        }

        Ascii num(long v) {
            return add(Long.toString(v));
        }

        Ascii cents(long v) {
            return num(v / 100).add(v % 100 < 10 ? ".0" : ".").num(v % 100);
        }

        int size() {
            return len;
        }

        byte[] bytes() {
            return Arrays.copyOf(buf, len);
        }
    }
}
