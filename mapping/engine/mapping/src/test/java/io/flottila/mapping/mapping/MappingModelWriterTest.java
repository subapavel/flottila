package io.flottila.mapping.mapping;

import static io.flottila.mapping.mapping.Fixtures.assertBytes;
import static io.flottila.mapping.mapping.Fixtures.utf8;
import static io.flottila.mapping.mapping.LoadResults.loaded;
import static io.flottila.mapping.mapping.Models.at;
import static io.flottila.mapping.mapping.Models.boxes;
import static io.flottila.mapping.mapping.Models.elementRoot;
import static io.flottila.mapping.mapping.Models.function;
import static io.flottila.mapping.mapping.Models.inputs;
import static io.flottila.mapping.mapping.Models.layout;
import static io.flottila.mapping.mapping.Models.model;
import static io.flottila.mapping.mapping.Models.names;
import static io.flottila.mapping.mapping.Models.noLayout;
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
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.flottila.mapping.structure.WsdlDirection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The canonical writer (ADR-0010, #24): one spelling per model. Every expected text below is a
 * literal of the canonical form, checked against ECMA-262 {@code JSON.stringify(value, null, 2)}
 * with keys sorted by UTF-16 code unit; none is derived from the code under test.
 */
class MappingModelWriterTest {

  static final String FP_ORDER =
      "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  static final String FP_INVOICE =
      "sha256:fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

  static final StructureRef ORDER = elementRoot("Order", FP_ORDER, "order.xsd");
  static final StructureRef INVOICE = elementRoot("Invoice", FP_INVOICE, "invoice.xsd");

  /** The canonical text of {@link #ORDER} and {@link #INVOICE}, up to the next member. */
  static final String HEADER =
      """
      {
        "formatVersion": 1,
        "source": {
          "bundle": [
            "order.xsd"
          ],
          "fingerprint": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
          "root": {
            "element": "Order"
          }
        },
        "target": {
          "bundle": [
            "invoice.xsd"
          ],
          "fingerprint": "sha256:fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
          "root": {
            "element": "Invoice"
          }
        },
      """;

  static final MappingModel MINIMAL =
      model(
          ORDER,
          INVOICE,
          targetFieldMappings(
              "/Invoice/Number",
              targetFieldMapping("t1", "a1", boxes("a1", source("/Order/Number")))),
          Map.of());

  static final String MINIMAL_TEXT =
      """
      {
        "formatVersion": 1,
        "source": {
          "bundle": [
            "order.xsd"
          ],
          "fingerprint": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
          "root": {
            "element": "Order"
          }
        },
        "target": {
          "bundle": [
            "invoice.xsd"
          ],
          "fingerprint": "sha256:fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
          "root": {
            "element": "Invoice"
          }
        },
        "targetFieldMappings": {
          "/Invoice/Number": {
            "boxes": {
              "a1": {
                "field": "/Order/Number",
                "kind": "source"
              }
            },
            "id": "t1",
            "result": "a1"
          }
        }
      }
      """;

  @Test
  void saveWritesLfIndentFinalLfWithoutBom() {
    byte[] saved = MappingModelFormat.save(MINIMAL);

    assertBytes(MINIMAL_TEXT, saved);
    assertThat(saved[0]).as("first byte, no BOM").isEqualTo((byte) '{');
    assertThat(saved).as("no CR anywhere").doesNotContain((byte) '\r');
    assertThat(saved[saved.length - 1]).as("final LF").isEqualTo((byte) '\n');
    assertThat(saved[saved.length - 2]).as("exactly one final LF").isEqualTo((byte) '}');
  }

  @Test
  void saveWritesFormatVersionAsIntegerOne() {
    MappingModel numericLooking =
        model(
            ORDER,
            INVOICE,
            targetFieldMappings(
                "/Invoice/Count",
                targetFieldMapping(
                    "t1",
                    "c1",
                    boxes("c1", function("constant", inputs(), params("value", text("2")))))),
            udfs(
                "pad",
                udfDeclaration(
                    ExecutionKind.SINGLE,
                    names(),
                    udfParams("width", UdfParamType.INT),
                    "u/U.java#pad")));

    String saved = new String(MappingModelFormat.save(numericLooking), UTF_8);

    assertThat(saved.lines().skip(1).findFirst()).hasValue("  \"formatVersion\": 1,");
    // formatVersion is the only number: outside string literals exactly one digit run remains
    String outsideStrings = saved.replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"");
    assertThat(outsideStrings.replaceAll("[^0-9]+", " ").trim()).isEqualTo("1");
    assertThat(saved).contains("\"value\": \"2\"").contains("\"width\": \"int\"");
  }

  @Test
  void saveOrdersKeysByUtf16CodeUnit() {
    MappingModel model =
        model(
            ORDER,
            wsdlRoot(
                "InvoicePort", "Send", WsdlDirection.FAULT, "Rejected", FP_INVOICE, "invoice.wsdl"),
            targetFieldMappings(
                "/Invoice/ItemList",
                targetFieldMapping("t3", "c1", boxes("c1", source("/Order/Items"))),
                "/Invoice/Item/@id",
                targetFieldMapping("t2", "b1", boxes("b1", source("/Order/Item/@id"))),
                "/Invoice/Item",
                targetFieldMapping(
                    "t1",
                    "b9",
                    boxes(
                        "b9",
                        function("constant", inputs(), params("value", text("x"))),
                        "b10",
                        function(
                            "fixValues",
                            inputs("_x"),
                            params(
                                "table",
                                table(
                                    "\uff5e", "fullwidth tilde",
                                    "\ud83d\ude00", "astral",
                                    "\u00e9", "e acute",
                                    "a", "lower",
                                    "B", "upper",
                                    "", "empty"))),
                        "a7",
                        udf("Alpha", inputs("_x"), params("scale", text("2"))),
                        "_x",
                        source("/Order/Item/@type"),
                        "Zu",
                        unresolved(
                            "CopyValue", Origin.PO, inputs("_x"), params("index", text("1"))),
                        "B1",
                        source("/Order/Item/@b"),
                        "-y",
                        source("/Order/Item/@y")))),
            udfs(
                "zeta",
                udfDeclaration(ExecutionKind.QUEUE, names(), Map.of(), "udf/Udfs.java#zeta"),
                "_beta",
                udfDeclaration(ExecutionKind.CONTEXT, names(), Map.of(), "udf/Udfs.java#_beta"),
                "Alpha",
                udfDeclaration(
                    ExecutionKind.SINGLE,
                    names("amount"),
                    udfParams("scale", UdfParamType.INT),
                    "udf/Udfs.java#Alpha")));

    assertBytes(
        """
        {
          "formatVersion": 1,
          "source": {
            "bundle": [
              "order.xsd"
            ],
            "fingerprint": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "root": {
              "element": "Order"
            }
          },
          "target": {
            "bundle": [
              "invoice.wsdl"
            ],
            "fingerprint": "sha256:fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
            "root": {
              "wsdl": {
                "direction": "fault",
                "fault": "Rejected",
                "interface": "InvoicePort",
                "operation": "Send"
              }
            }
          },
          "targetFieldMappings": {
            "/Invoice/Item": {
              "boxes": {
                "-y": {
                  "field": "/Order/Item/@y",
                  "kind": "source"
                },
                "B1": {
                  "field": "/Order/Item/@b",
                  "kind": "source"
                },
                "Zu": {
                  "inputs": [
                    "_x"
                  ],
                  "kind": "unresolved",
                  "name": "CopyValue",
                  "origin": "po",
                  "params": {
                    "index": "1"
                  }
                },
                "_x": {
                  "field": "/Order/Item/@type",
                  "kind": "source"
                },
                "a7": {
                  "inputs": [
                    "_x"
                  ],
                  "kind": "udf",
                  "params": {
                    "scale": "2"
                  },
                  "udf": "Alpha"
                },
                "b10": {
                  "fn": "fixValues",
                  "inputs": [
                    "_x"
                  ],
                  "kind": "function",
                  "params": {
                    "table": {
                      "": "empty",
                      "B": "upper",
                      "a": "lower",
                      "\u00e9": "e acute",
                      "\ud83d\ude00": "astral",
                      "\uff5e": "fullwidth tilde"
                    }
                  }
                },
                "b9": {
                  "fn": "constant",
                  "kind": "function",
                  "params": {
                    "value": "x"
                  }
                }
              },
              "id": "t1",
              "result": "b9"
            },
            "/Invoice/Item/@id": {
              "boxes": {
                "b1": {
                  "field": "/Order/Item/@id",
                  "kind": "source"
                }
              },
              "id": "t2",
              "result": "b1"
            },
            "/Invoice/ItemList": {
              "boxes": {
                "c1": {
                  "field": "/Order/Items",
                  "kind": "source"
                }
              },
              "id": "t3",
              "result": "c1"
            }
          },
          "udfs": {
            "Alpha": {
              "executionKind": "single",
              "inputs": [
                "amount"
              ],
              "params": {
                "scale": "int"
              },
              "source": "udf/Udfs.java#Alpha"
            },
            "_beta": {
              "executionKind": "context",
              "source": "udf/Udfs.java#_beta"
            },
            "zeta": {
              "executionKind": "queue",
              "source": "udf/Udfs.java#zeta"
            }
          }
        }
        """,
        MappingModelFormat.save(model));
  }

  /**
   * The value holds every escape class of {@code JSON.stringify}: the two-character escapes, other
   * C0 characters and lone surrogates as lowercase {@code \\uxxxx}; DEL, U+00E9, U+FEFF, U+2028,
   * U+2029, a surrogate pair and {@code /} stay literal.
   */
  static final String ESCAPE_VALUE =
      "q:\" b:\\ s:/ bs:\b ff:\f nl:\n cr:\r tab:\t nul:\u0000 soh:\u0001 us:\u001f"
          + " del:\u007f e:\u00e9 bom:\ufeff ls:\u2028 ps:\u2029 pair:\ud83d\ude00"
          + " high:\ud83d| low:\ude00| rev:\ude00\ud83d end";

  static final String ESCAPE_VALUE_JSON =
      "\"q:\\\" b:\\\\ s:/ bs:\\b ff:\\f nl:\\n cr:\\r tab:\\t nul:\\u0000 soh:\\u0001 us:\\u001f"
          + " del:\u007f e:\u00e9 bom:\ufeff ls:\u2028 ps:\u2029 pair:\ud83d\ude00"
          + " high:\\ud83d| low:\\ude00| rev:\\ude00\\ud83d end\"";

  @Test
  void saveEscapesStringsLikeJsonStringify() {
    MappingModel model =
        model(
            ORDER,
            INVOICE,
            targetFieldMappings(
                "/Invoice/Note",
                targetFieldMapping(
                    "t1",
                    "k1",
                    boxes(
                        "k1",
                        function("constant", inputs(), params("value", text(ESCAPE_VALUE))),
                        "k2",
                        function(
                            "fixValues",
                            inputs("k1"),
                            params(
                                "table",
                                table(
                                    "back\\slash",
                                    "tab\there",
                                    "\"q\"",
                                    "nul\u0000",
                                    "\u0001",
                                    "")))))),
            Map.of());

    assertBytes(
        HEADER
            + """
              "targetFieldMappings": {
                "/Invoice/Note": {
                  "boxes": {
                    "k1": {
                      "fn": "constant",
                      "kind": "function",
                      "params": {
                        "value": \
            """
            + ESCAPE_VALUE_JSON
            + "\n"
            + """
                      }
                    },
                    "k2": {
                      "fn": "fixValues",
                      "inputs": [
                        "k1"
                      ],
                      "kind": "function",
                      "params": {
                        "table": {
                          "\\u0001": "",
                          "\\"q\\"": "nul\\u0000",
                          "back\\\\slash": "tab\\there"
                        }
                      }
                    }
                  },
                  "id": "t1",
                  "result": "k1"
                }
              }
            }
            """,
        MappingModelFormat.save(model));
  }

  @Test
  void saveLeavesOutEmptyCollections() {
    MappingModel bare = model(ORDER, INVOICE, Map.of(), Map.of());
    MappingModel emptyInside =
        model(
            ORDER,
            INVOICE,
            targetFieldMappings(
                "/Invoice/Date",
                targetFieldMapping(
                    "t1",
                    "f1",
                    boxes(
                        "x1",
                        unresolved("DateNow", Origin.EDITOR, inputs(), noParams()),
                        "u1",
                        udf("today", inputs(), noParams()),
                        "f1",
                        function("currentDate", inputs(), noParams())))),
            udfs(
                "today",
                udfDeclaration(ExecutionKind.SINGLE, List.of(), Map.of(), "udf/Dates.java#today")));

    assertBytes(
        """
        {
          "formatVersion": 1,
          "source": {
            "bundle": [
              "order.xsd"
            ],
            "fingerprint": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "root": {
              "element": "Order"
            }
          },
          "target": {
            "bundle": [
              "invoice.xsd"
            ],
            "fingerprint": "sha256:fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
            "root": {
              "element": "Invoice"
            }
          }
        }
        """,
        MappingModelFormat.save(bare));
    assertBytes(
        HEADER
            + """
              "targetFieldMappings": {
                "/Invoice/Date": {
                  "boxes": {
                    "f1": {
                      "fn": "currentDate",
                      "kind": "function"
                    },
                    "u1": {
                      "kind": "udf",
                      "udf": "today"
                    },
                    "x1": {
                      "kind": "unresolved",
                      "name": "DateNow",
                      "origin": "editor"
                    }
                  },
                  "id": "t1",
                  "result": "f1"
                }
              },
              "udfs": {
                "today": {
                  "executionKind": "single",
                  "source": "udf/Dates.java#today"
                }
              }
            }
            """,
        MappingModelFormat.save(emptyInside));
  }

  @Test
  void saveWritesEmptyTableAsEmptyObject() {
    MappingModel model =
        model(
            ORDER,
            INVOICE,
            targetFieldMappings(
                "/Invoice/Currency",
                targetFieldMapping(
                    "t1",
                    "b1",
                    boxes(
                        "b1",
                        function("fixValues", inputs("a1"), params("table", table())),
                        "a1",
                        source("/Order/Currency")))),
            Map.of());

    assertBytes(
        HEADER
            + """
              "targetFieldMappings": {
                "/Invoice/Currency": {
                  "boxes": {
                    "a1": {
                      "field": "/Order/Currency",
                      "kind": "source"
                    },
                    "b1": {
                      "fn": "fixValues",
                      "inputs": [
                        "a1"
                      ],
                      "kind": "function",
                      "params": {
                        "table": {}
                      }
                    }
                  },
                  "id": "t1",
                  "result": "b1"
                }
              }
            }
            """,
        MappingModelFormat.save(model));
  }

  @Test
  void saveWritesEmptyTextAsEmptyString() {
    MappingModel model =
        model(
            ORDER,
            INVOICE,
            targetFieldMappings(
                "/Invoice/Note",
                targetFieldMapping(
                    "t1",
                    "c1",
                    boxes("c1", function("constant", inputs(), params("value", text("")))))),
            Map.of());

    assertBytes(
        HEADER
            + """
              "targetFieldMappings": {
                "/Invoice/Note": {
                  "boxes": {
                    "c1": {
                      "fn": "constant",
                      "kind": "function",
                      "params": {
                        "value": ""
                      }
                    }
                  },
                  "id": "t1",
                  "result": "c1"
                }
              }
            }
            """,
        MappingModelFormat.save(model));
  }

  @Test
  void saveLeavesOutContextEqualToParent() {
    String ns = "Q{http://example.com/order}";
    MappingModel model =
        model(
            ORDER,
            INVOICE,
            targetFieldMappings(
                "/Invoice/Line/@id",
                targetFieldMapping(
                    "t1",
                    "a1",
                    boxes(
                        "a7",
                        source(
                            "/" + ns + "Order/" + ns + "Item/" + ns + "Price", "/" + ns + "Order"),
                        "a6",
                        source("/Order/Item"),
                        "a5",
                        source("/$/order/items/id", "/$/order"),
                        "a4",
                        source("/$/order/items/id", "/$/order/items"),
                        "a3",
                        source("/" + ns + "Order/" + ns + "Item", "/" + ns + "Order"),
                        "a2",
                        source("/Order/Item/@id", "/Order"),
                        "a1",
                        source("/Order/Item/@id", "/Order/Item")))),
            Map.of());

    assertBytes(
        HEADER
            + """
              "targetFieldMappings": {
                "/Invoice/Line/@id": {
                  "boxes": {
                    "a1": {
                      "field": "/Order/Item/@id",
                      "kind": "source"
                    },
                    "a2": {
                      "context": "/Order",
                      "field": "/Order/Item/@id",
                      "kind": "source"
                    },
                    "a3": {
                      "field": "/Q{http://example.com/order}Order/Q{http://example.com/order}Item",
                      "kind": "source"
                    },
                    "a4": {
                      "field": "/$/order/items/id",
                      "kind": "source"
                    },
                    "a5": {
                      "context": "/$/order",
                      "field": "/$/order/items/id",
                      "kind": "source"
                    },
                    "a6": {
                      "field": "/Order/Item",
                      "kind": "source"
                    },
                    "a7": {
                      "context": "/Q{http://example.com/order}Order",
                      "field": "/Q{http://example.com/order}Order/Q{http://example.com/order}Item/Q{http://example.com/order}Price",
                      "kind": "source"
                    }
                  },
                  "id": "t1",
                  "result": "a1"
                }
              }
            }
            """,
        MappingModelFormat.save(model));
  }

  @Test
  void saveKeepsNullResultAndUnconnectedInputs() {
    MappingModel model =
        model(
            ORDER,
            INVOICE,
            targetFieldMappings(
                "/Invoice/Name",
                targetFieldMapping(
                    "t1",
                    null,
                    boxes(
                        "b3",
                        unresolved("toUpperCase", Origin.CPI, inputs((String) null), noParams()),
                        "b2",
                        function("concat", inputs(null, null), noParams()),
                        "b1",
                        function("concat", inputs(null, "a1", null), noParams()),
                        "a1",
                        source("/Order/Name")))),
            Map.of());

    assertBytes(
        HEADER
            + """
              "targetFieldMappings": {
                "/Invoice/Name": {
                  "boxes": {
                    "a1": {
                      "field": "/Order/Name",
                      "kind": "source"
                    },
                    "b1": {
                      "fn": "concat",
                      "inputs": [
                        null,
                        "a1",
                        null
                      ],
                      "kind": "function"
                    },
                    "b2": {
                      "fn": "concat",
                      "inputs": [
                        null,
                        null
                      ],
                      "kind": "function"
                    },
                    "b3": {
                      "inputs": [
                        null
                      ],
                      "kind": "unresolved",
                      "name": "toUpperCase",
                      "origin": "cpi"
                    }
                  },
                  "id": "t1",
                  "result": null
                }
              }
            }
            """,
        MappingModelFormat.save(model));
  }

  // --- the studio members (ADR-0029): a description and a layout per target field mapping, which
  // the writer keeps, sorted as the rest of the file; it drops a stale position and leaves empties
  // out. These read the members with load, which stage 1 has to accept for them.

  /**
   * A position whose box is not one of its own target field mapping's boxes is dropped on save:
   * {@code zz} names no box at all, and {@code s2} in t1 and {@code a1} in t2 name a box of the
   * other target field mapping. Each layout keeps one position, so it is the stale one that goes,
   * not the whole layout.
   */
  @Test
  void saveDropsAPositionWhoseBoxIsNotInItsTargetFieldMapping() throws Exception {
    String stale =
        HEADER
            + """
              "targetFieldMappings": {
                "/Invoice/Date": {"id": "t2", "result": "s2",
                  "boxes": {"s2": {"kind": "source", "field": "/Order/Date"}},
                  "layout": {"boxes": {"zz": {"x": 1, "y": 2}, "s2": {"x": 3, "y": 4},
                                       "a1": {"x": 5, "y": 6}}}},
                "/Invoice/Number": {"id": "t1", "result": "a1",
                  "boxes": {"a1": {"kind": "source", "field": "/Order/Number"}},
                  "layout": {"target": {"x": 7, "y": 8}, "boxes": {"s2": {"x": 9, "y": 10}}}}
              }
            }
            """;

    byte[] saved = MappingModelFormat.save(loaded(MappingModelFormat.load(utf8(stale))));

    assertBytes(
        HEADER
            + """
              "targetFieldMappings": {
                "/Invoice/Date": {
                  "boxes": {
                    "s2": {
                      "field": "/Order/Date",
                      "kind": "source"
                    }
                  },
                  "id": "t2",
                  "layout": {
                    "boxes": {
                      "s2": {
                        "x": 3,
                        "y": 4
                      }
                    }
                  },
                  "result": "s2"
                },
                "/Invoice/Number": {
                  "boxes": {
                    "a1": {
                      "field": "/Order/Number",
                      "kind": "source"
                    }
                  },
                  "id": "t1",
                  "layout": {
                    "target": {
                      "x": 7,
                      "y": 8
                    }
                  },
                  "result": "a1"
                }
              }
            }
            """,
        saved);
  }

  /**
   * An empty description, an empty layout and an empty {@code layout.boxes} are left out, and so is
   * a layout that holds only stale positions; a layout with a target alone keeps its target.
   */
  @Test
  void saveLeavesOutAnEmptyDescriptionLayoutAndLayoutBoxes() throws Exception {
    String empties =
        "{\n  \"description\": \"\",\n"
            + HEADER.substring(2)
            + """
              "targetFieldMappings": {
                "/Invoice/A": {"id": "t1", "result": "a1", "layout": {},
                  "boxes": {"a1": {"kind": "source", "field": "/Order/A"}}},
                "/Invoice/B": {"id": "t2", "result": "a2", "layout": {"boxes": {}},
                  "boxes": {"a2": {"kind": "source", "field": "/Order/B"}}},
                "/Invoice/C": {"id": "t3", "result": "a3", "layout": {"boxes": {"zz": {"x": 0, "y": 0}}},
                  "boxes": {"a3": {"kind": "source", "field": "/Order/C"}}},
                "/Invoice/D": {"id": "t4", "result": "a4",
                  "layout": {"boxes": {}, "target": {"x": 0, "y": 0}},
                  "boxes": {"a4": {"kind": "source", "field": "/Order/D"}}}
              }
            }
            """;
    assertThat(empties).startsWith("{\n  \"description\": \"\",\n  \"formatVersion\": 1,\n");

    byte[] saved = MappingModelFormat.save(loaded(MappingModelFormat.load(utf8(empties))));

    assertBytes(
        HEADER
            + """
              "targetFieldMappings": {
                "/Invoice/A": {
                  "boxes": {
                    "a1": {
                      "field": "/Order/A",
                      "kind": "source"
                    }
                  },
                  "id": "t1",
                  "result": "a1"
                },
                "/Invoice/B": {
                  "boxes": {
                    "a2": {
                      "field": "/Order/B",
                      "kind": "source"
                    }
                  },
                  "id": "t2",
                  "result": "a2"
                },
                "/Invoice/C": {
                  "boxes": {
                    "a3": {
                      "field": "/Order/C",
                      "kind": "source"
                    }
                  },
                  "id": "t3",
                  "result": "a3"
                },
                "/Invoice/D": {
                  "boxes": {
                    "a4": {
                      "field": "/Order/D",
                      "kind": "source"
                    }
                  },
                  "id": "t4",
                  "layout": {
                    "target": {
                      "x": 0,
                      "y": 0
                    }
                  },
                  "result": "a4"
                }
              }
            }
            """,
        saved);
  }

  /**
   * The record drops a stale position itself (ADR-0029), so no model holds one: neither one a test
   * builds nor one a caller saves.
   */
  @Test
  void aTargetFieldMappingKeepsOnlyThePositionsOfItsOwnBoxes() {
    TargetFieldMapping mapping =
        targetFieldMapping(
            "t1",
            "a1",
            boxes("a1", source("/Order/Number")),
            layout(at(7, 8), "zz", at(1, 2), "a1", at(-3, 4), "t1", at(5, 6)));

    assertThat(mapping.layout()).isEqualTo(layout(at(7, 8), "a1", at(-3, 4)));
    assertThat(targetFieldMapping("t2", null, boxes(), layout(null, "zz", at(1, 2))).layout())
        .isEqualTo(noLayout());
  }

  @Test
  void saveOfARecordWithAStalePositionWritesOnlyTheOthers() {
    MappingModel model =
        model(
            ORDER,
            INVOICE,
            targetFieldMappings(
                "/Invoice/Number",
                targetFieldMapping(
                    "t1",
                    "a1",
                    boxes("a1", source("/Order/Number")),
                    layout(null, "zz", at(1, 2), "a1", at(-3, 4)))),
            Map.of());

    assertBytes(
        utf8(
            HEADER
                + """
                  "targetFieldMappings": {
                    "/Invoice/Number": {
                      "boxes": {
                        "a1": {
                          "field": "/Order/Number",
                          "kind": "source"
                        }
                      },
                      "id": "t1",
                      "layout": {
                        "boxes": {
                          "a1": {
                            "x": -3,
                            "y": 4
                          }
                        }
                      },
                      "result": "a1"
                    }
                  }
                }
                """),
        MappingModelFormat.save(model));
  }

  @Test
  void saveOfARecordLeavesOutAnEmptyDescriptionAndAnEmptyLayout() {
    MappingModel model =
        model(
            Optional.of(""),
            Optional.of(ORDER),
            Optional.of(INVOICE),
            targetFieldMappings(
                "/Invoice/Number",
                targetFieldMapping("t1", "a1", boxes("a1", source("/Order/Number")), noLayout())),
            Map.of());

    assertBytes(utf8(MINIMAL_TEXT), MappingModelFormat.save(model));
  }

  @Test
  void saveOfAModelWithoutStructuresWritesItsFormatVersionAlone() {
    MappingModel bare =
        model(Optional.empty(), Optional.empty(), Optional.empty(), Map.of(), Map.of());

    assertBytes(utf8("{\n  \"formatVersion\": 1\n}\n"), MappingModelFormat.save(bare));
  }

  @Test
  void saveWritesTheDescriptionFirstAndOnlyTheSideAModelHas() {
    MappingModel onlyTarget =
        model(
            Optional.of("Rechnung – Entwurf"),
            Optional.empty(),
            Optional.of(INVOICE),
            Map.of(),
            Map.of());

    assertBytes(
        utf8(
            """
            {
              "description": "Rechnung – Entwurf",
              "formatVersion": 1,
              "target": {
                "bundle": [
                  "invoice.xsd"
                ],
                "fingerprint": "sha256:fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
                "root": {
                  "element": "Invoice"
                }
              }
            }
            """),
        MappingModelFormat.save(onlyTarget));
  }

  @Test
  void saveSortsBundleFiles() {
    MappingModel model =
        model(
            elementRoot(
                "Order",
                FP_ORDER,
                "b.xsd",
                "common-schemas@1.4.0:/order/order.xsd",
                "a.xsd",
                "B.xsd",
                "../common/types.xsd"),
            elementRoot("Invoice", FP_INVOICE, "z.wsdl", "y.xsd"),
            Map.of(),
            Map.of());

    assertBytes(
        """
        {
          "formatVersion": 1,
          "source": {
            "bundle": [
              "../common/types.xsd",
              "B.xsd",
              "a.xsd",
              "b.xsd",
              "common-schemas@1.4.0:/order/order.xsd"
            ],
            "fingerprint": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "root": {
              "element": "Order"
            }
          },
          "target": {
            "bundle": [
              "y.xsd",
              "z.wsdl"
            ],
            "fingerprint": "sha256:fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
            "root": {
              "element": "Invoice"
            }
          }
        }
        """,
        MappingModelFormat.save(model));
  }
}
