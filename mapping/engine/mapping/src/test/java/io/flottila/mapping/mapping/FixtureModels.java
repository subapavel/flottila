package io.flottila.mapping.mapping;

import static io.flottila.mapping.mapping.Models.at;
import static io.flottila.mapping.mapping.Models.boxes;
import static io.flottila.mapping.mapping.Models.elementRoot;
import static io.flottila.mapping.mapping.Models.function;
import static io.flottila.mapping.mapping.Models.inputs;
import static io.flottila.mapping.mapping.Models.jsonRoot;
import static io.flottila.mapping.mapping.Models.layout;
import static io.flottila.mapping.mapping.Models.model;
import static io.flottila.mapping.mapping.Models.names;
import static io.flottila.mapping.mapping.Models.noParams;
import static io.flottila.mapping.mapping.Models.params;
import static io.flottila.mapping.mapping.Models.source;
import static io.flottila.mapping.mapping.Models.table;
import static io.flottila.mapping.mapping.Models.targetFieldMapping;
import static io.flottila.mapping.mapping.Models.targetFieldMappings;
import static io.flottila.mapping.mapping.Models.text;
import static io.flottila.mapping.mapping.Models.udf;
import static io.flottila.mapping.mapping.Models.udfDeclaration;
import static io.flottila.mapping.mapping.Models.udfParams;
import static io.flottila.mapping.mapping.Models.udfs;
import static io.flottila.mapping.mapping.Models.unresolved;
import static io.flottila.mapping.mapping.Models.wsdlRoot;

import io.flottila.mapping.structure.WsdlDirection;
import java.util.Map;
import java.util.Optional;

/**
 * The models the committed fixtures under {@code engine/mapping/src/test/resources/mapping-model}
 * describe, written by hand from the files: the worked examples a load is compared with.
 */
final class FixtureModels {

  private FixtureModels() {}

  private static final String ORD = "Q{urn:flottila:test:order}";
  private static final String INV = "Q{urn:flottila:test:invoice}";

  /** {@code every-box-kind.mapping.json}. */
  static MappingModel everyBoxKind() {
    return model(
        elementRoot(
            ORD + "Order",
            "sha256:eb9557e1dbe93ad203f075592afed56672eb9f5fbd742d0260e04d12a37d86fc",
            "../structures/order-types.xsd",
            "../structures/order.xsd"),
        wsdlRoot(
            INV + "InvoicePortType",
            "SendInvoice",
            WsdlDirection.FAULT,
            "InvoiceRejected",
            "sha256:25cbda891fc394e70ba37c3794f34d10778f8d2d1e4f211c9fd58ca8666eff4f",
            "common-schemas@1.4.0:/invoice/invoice.wsdl"),
        targetFieldMappings(
            "/" + INV + "InvoiceRejection/" + INV + "Amount",
            targetFieldMapping(
                "0a3c5e7g9j",
                "8e4w6y2j0p",
                boxes(
                    "2v9d0k5mxs",
                    source(
                        "/" + ORD + "Order/" + ORD + "Item/" + ORD + "Price", "/" + ORD + "Order"),
                    "5h7b3n1qgz",
                    udf("roundAmount", inputs("2v9d0k5mxs", null), params("scale", text("2"))),
                    "8e4w6y2j0p",
                    function(
                        "formatNumber", inputs("5h7b3n1qgz"), params("pattern", text("#0.00"))))),
            "/" + INV + "InvoiceRejection/" + INV + "Currency",
            targetFieldMapping(
                "0b4d6f8h0k",
                "4m2tz9r0ha",
                boxes(
                    "1c8kq3v7we",
                    source("/" + ORD + "Order/" + ORD + "Header/@currency"),
                    "4m2tz9r0ha",
                    function(
                        "fixValues",
                        inputs("1c8kq3v7we"),
                        params(
                            "defaultValue",
                            text("EUR"),
                            "table",
                            table("", "EUR", "CHF", "CHF", "dollar", "USD", "euro", "EUR"))))),
            "/" + INV + "InvoiceRejection/" + INV + "Reason",
            targetFieldMapping(
                "0c5e7g9j1m",
                null,
                boxes(
                    "3r5t7v9x1z",
                    unresolved(
                        "CopyValue",
                        Origin.PO,
                        inputs(null, "6k8m0p2r4t"),
                        params("index", text("1"))),
                    "6k8m0p2r4t",
                    function("constant", inputs(), params("value", text("rejected"))),
                    "7n9q1s3v5x",
                    function("fixValues", inputs("6k8m0p2r4t"), params("table", table())),
                    "9w1y3a5c7e",
                    unresolved("formatByExample", Origin.CPI, inputs("3r5t7v9x1z"), noParams()),
                    "9x2z4b6d8f",
                    unresolved("UserLookup", Origin.EDITOR, inputs(), noParams())))),
        udfs(
            "headerFlag",
            udfDeclaration(
                ExecutionKind.QUEUE,
                names(),
                udfParams("mode", UdfParamType.STRING),
                "../udfs/test/Amounts.java#headerFlag"),
            "roundAmount",
            udfDeclaration(
                ExecutionKind.SINGLE,
                names("amount", "fallback"),
                udfParams("scale", UdfParamType.INT),
                "../udfs/test/Amounts.java#roundAmount"),
            "sumByKey",
            udfDeclaration(
                ExecutionKind.CONTEXT,
                names("keys", "values"),
                Map.of(),
                "../udfs/test/Amounts.java#sumByKey")));
  }

  /** {@code new-mapping.mapping.json}: a mapping as created, with nothing mapped yet. */
  static MappingModel newMapping() {
    return model(
        elementRoot(
            "Partner",
            "sha256:60a6987ab0d4a74971b939fe7b9dbef12f0fe51a3cfced4abb5ca170f5271d9c",
            "../structures/partner.xsd"),
        wsdlRoot(
            "PartnerService",
            "UpsertPartner",
            WsdlDirection.INPUT,
            null,
            "sha256:cc42b2f7cf5504b7335dda523f9e08ebd7521288ada1c60607c588b6c0d854a3",
            "../structures/partner-service.wsdl",
            "../structures/partner-types.xsd"),
        Map.of(),
        Map.of());
  }

  /**
   * {@code studio-members.mapping.json} (ADR-0029): a description, a target field mapping without a
   * layout, one with only its target's position, one with every coordinate limit, one with only a
   * box position.
   */
  static MappingModel studioMembers() {
    return model(
        Optional.of("Orders to invoices, laid out in the studio.\nZweite Zeile: \"Größe\" 😀"),
        Optional.of(
            elementRoot(
                "Order",
                "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "../structures/order.xsd")),
        Optional.of(
            elementRoot(
                "Invoice",
                "sha256:fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
                "../structures/invoice.xsd")),
        targetFieldMappings(
            "/Invoice/Currency",
            targetFieldMapping("t4", "s4", boxes("s4", source("/Order/Currency"))),
            "/Invoice/Date",
            targetFieldMapping("t3", "s3", boxes("s3", source("/Order/Date")), layout(at(640, 0))),
            "/Invoice/Number",
            targetFieldMapping(
                "t1",
                "B2",
                boxes(
                    "B2",
                    function("concat", inputs("_p", "a1"), noParams()),
                    "_p",
                    source("/Order/Prefix"),
                    "a1",
                    source("/Order/Number")),
                layout(
                    at(1_000_000, -16),
                    "B2",
                    at(0, -1_000_000),
                    "_p",
                    at(-240, 1_000_000),
                    "a1",
                    at(-1_000_000, 80))),
            "/Invoice/Total",
            targetFieldMapping(
                "t2",
                "c2",
                boxes("c2", function("constant", inputs(), params("value", text("0")))),
                layout(null, "c2", at(12, 34)))),
        Map.of());
  }

  /** {@code no-structures.mapping.json}: a mapping created from its id alone (ADR-0029). */
  static MappingModel noStructures() {
    return model(Optional.empty(), Optional.empty(), Optional.empty(), Map.of(), Map.of());
  }

  /** {@code escapes.mapping.json}: every escape class of {@code JSON.stringify}, JSON roots. */
  static MappingModel escapes() {
    return model(
        jsonRoot(
            "sha256:1a1ccb81d562a8b476ab312751cbb7445babc727daf94e7a96ef39f553a64530",
            "../structures/note.schema.json"),
        jsonRoot(
            "sha256:9c30c670477f2ff71ab91ad4c1007989d62ed65b23ece3e7151ba029d12486f0",
            "../structures/note-out.schema.json"),
        targetFieldMappings(
            "/$/note/text",
            targetFieldMapping(
                "t",
                "f",
                boxes(
                    "s",
                    source("/$/note/kind"),
                    "f",
                    function(
                        "fixValues",
                        inputs("s"),
                        params(
                            "table",
                            table(
                                "", "empty key",
                                "\u0001", "soh key",
                                "\"q\"", "quoted key",
                                "A", "upper",
                                "a", "lower",
                                "é", "e acute",
                                "😀", "astral, sorts before U+FF5E by UTF-16 code unit",
                                "～", "fullwidth tilde",
                                "back\\slash", "tab\there"))),
                    "Constant-With_Mixed-Case-and-the-longest-id-the-schema-allows-00",
                    function(
                        "constant",
                        inputs(),
                        params("value", text(MappingModelWriterTest.ESCAPE_VALUE)))))),
        Map.of());
  }
}
