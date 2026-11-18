import{t as e}from"./index-eaP4RbxI.js";var t=`<?xml version="1.0" encoding="UTF-8"?>
<ns0:Order xmlns:ns0="urn:rail8:corpus:skeleton">
  <OrderId>PO 4711</OrderId>
</ns0:Order>
`,n=`<?xml version="1.0" encoding="UTF-8"?>
<src:Order xmlns:src="urn:rail8:corpus:skeleton" channel="web">
  <OrderId>PO 4711</OrderId>
  <Quantity>3</Quantity>
</src:Order>
`,r=`{
  "formatVersion": 1,
  "source": {
    "bundle": [
      "../structures/skeleton/order.xsd"
    ],
    "fingerprint": "sha256:28ec3b615bbf0802cad0e676d1f5cf36d57f95216448a3653cac7b722b7ca87a",
    "root": {
      "element": "Q{urn:rail8:corpus:skeleton}Order"
    }
  },
  "target": {
    "bundle": [
      "../structures/skeleton/order.xsd"
    ],
    "fingerprint": "sha256:28ec3b615bbf0802cad0e676d1f5cf36d57f95216448a3653cac7b722b7ca87a",
    "root": {
      "element": "Q{urn:rail8:corpus:skeleton}Order"
    }
  },
  "targetFieldMappings": {
    "/Q{urn:rail8:corpus:skeleton}Order/OrderId": {
      "boxes": {
        "a1": {
          "field": "/Q{urn:rail8:corpus:skeleton}Order/OrderId",
          "kind": "source"
        }
      },
      "id": "t1",
      "result": "a1"
    }
  }
}
`,i=`{
  "abs": {
    "executionKind": "single",
    "family": "arithmetic",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "value"
      }
    ],
    "portable": true
  },
  "add": {
    "executionKind": "single",
    "family": "arithmetic",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "a"
      },
      {
        "name": "b"
      }
    ],
    "portable": true
  },
  "and": {
    "executionKind": "single",
    "family": "boolean",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "a"
      },
      {
        "name": "b"
      }
    ],
    "portable": true
  },
  "average": {
    "executionKind": "context",
    "family": "statistic",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "values"
      }
    ],
    "portable": true
  },
  "ceil": {
    "executionKind": "single",
    "family": "arithmetic",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "value"
      }
    ],
    "portable": true
  },
  "collapseContexts": {
    "executionKind": "queue",
    "family": "context",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "queue"
      }
    ],
    "portable": true
  },
  "compare": {
    "executionKind": "single",
    "family": "text",
    "fidelity": {
      "level": "deviation",
      "text": "Compares text by Unicode character (code point). SAP PO compares UTF-16 units, so text with an emoji or another character beyond the Basic Multilingual Plane can sort differently."
    },
    "inputs": [
      {
        "name": "a"
      },
      {
        "name": "b"
      }
    ],
    "portable": true
  },
  "compareDates": {
    "executionKind": "single",
    "family": "date",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "date1"
      },
      {
        "name": "date2"
      }
    ],
    "params": [
      {
        "default": "yyyy/MM/dd",
        "name": "format1",
        "syntax": "datePattern",
        "type": "string"
      },
      {
        "default": "yyyy/MM/dd",
        "name": "format2",
        "syntax": "datePattern",
        "type": "string"
      }
    ],
    "portable": true
  },
  "concat": {
    "executionKind": "single",
    "family": "text",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "a"
      },
      {
        "name": "b"
      }
    ],
    "portable": true
  },
  "constant": {
    "executionKind": "single",
    "family": "constant",
    "fidelity": {
      "level": "exact"
    },
    "params": [
      {
        "default": "",
        "name": "value",
        "type": "string"
      }
    ],
    "portable": true
  },
  "copyValue": {
    "executionKind": "queue",
    "family": "constant",
    "fidelity": {
      "level": "deviation",
      "text": "Its result cannot feed a box that takes a single value, such as concat. SAP PO accepts that graph."
    },
    "generating": true,
    "inputs": [
      {
        "name": "values"
      }
    ],
    "params": [
      {
        "default": "0",
        "name": "position",
        "type": "string"
      }
    ],
    "portable": true
  },
  "count": {
    "executionKind": "context",
    "family": "statistic",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "values"
      }
    ],
    "portable": true
  },
  "counter": {
    "executionKind": "queue",
    "family": "statistic",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "values"
      }
    ],
    "params": [
      {
        "default": "1",
        "name": "initial",
        "type": "string"
      },
      {
        "default": "1",
        "name": "increment",
        "type": "string"
      }
    ],
    "portable": true
  },
  "createIf": {
    "executionKind": "context",
    "family": "context",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "condition"
      }
    ],
    "portable": true
  },
  "currentDate": {
    "executionKind": "single",
    "family": "date",
    "fidelity": {
      "level": "exact"
    },
    "params": [
      {
        "default": "yyyy/MM/dd",
        "name": "targetFormat",
        "syntax": "datePattern",
        "type": "string"
      }
    ],
    "portable": true
  },
  "dateAfter": {
    "executionKind": "single",
    "family": "date",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "date1"
      },
      {
        "name": "date2"
      }
    ],
    "params": [
      {
        "default": "yyyy/MM/dd",
        "name": "format1",
        "syntax": "datePattern",
        "type": "string"
      },
      {
        "default": "yyyy/MM/dd",
        "name": "format2",
        "syntax": "datePattern",
        "type": "string"
      }
    ],
    "portable": true
  },
  "dateBefore": {
    "executionKind": "single",
    "family": "date",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "date1"
      },
      {
        "name": "date2"
      }
    ],
    "params": [
      {
        "default": "yyyy/MM/dd",
        "name": "format1",
        "syntax": "datePattern",
        "type": "string"
      },
      {
        "default": "yyyy/MM/dd",
        "name": "format2",
        "syntax": "datePattern",
        "type": "string"
      }
    ],
    "portable": true
  },
  "dateTrans": {
    "executionKind": "single",
    "family": "date",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "date"
      }
    ],
    "params": [
      {
        "default": "yyyy/MM/dd",
        "name": "sourceFormat",
        "syntax": "datePattern",
        "type": "string"
      },
      {
        "default": "yyyy/MM/dd",
        "name": "targetFormat",
        "syntax": "datePattern",
        "type": "string"
      }
    ],
    "portable": true
  },
  "divide": {
    "executionKind": "single",
    "family": "arithmetic",
    "fidelity": {
      "level": "deviation",
      "text": "Divides with a fixed number of decimal places (as in XPath), so a very small quotient can become 0. SAP PO keeps 17 significant digits."
    },
    "inputs": [
      {
        "name": "dividend"
      },
      {
        "name": "divisor"
      }
    ],
    "portable": true
  },
  "endsWith": {
    "executionKind": "single",
    "family": "text",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "value"
      },
      {
        "name": "suffix"
      }
    ],
    "portable": true
  },
  "equals": {
    "executionKind": "single",
    "family": "boolean",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "a"
      },
      {
        "name": "b"
      }
    ],
    "portable": true
  },
  "equalsA": {
    "executionKind": "single",
    "family": "arithmetic",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "a"
      },
      {
        "name": "b"
      }
    ],
    "portable": true
  },
  "equalsS": {
    "executionKind": "single",
    "family": "text",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "a"
      },
      {
        "name": "b"
      }
    ],
    "portable": true
  },
  "exists": {
    "executionKind": "context",
    "family": "context",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "queue"
      }
    ],
    "portable": true
  },
  "fixValues": {
    "executionKind": "single",
    "family": "conversions",
    "fidelity": {
      "level": "deviation",
      "text": "A fresh FixValues box in SAP PO gives an empty value for a key the table lacks (\\"Use default value\\"). Rail8 gives the key itself, as SAP Cloud Integration does."
    },
    "inputs": [
      {
        "name": "key"
      }
    ],
    "params": [
      {
        "default": {},
        "name": "table",
        "type": "table"
      },
      {
        "default": "key",
        "name": "missing",
        "type": "string",
        "values": [
          "key",
          "default",
          "error"
        ]
      },
      {
        "default": "",
        "name": "defaultValue",
        "type": "string"
      }
    ],
    "portable": true
  },
  "floor": {
    "executionKind": "single",
    "family": "arithmetic",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "value"
      }
    ],
    "portable": true
  },
  "formatByExample": {
    "executionKind": "queue",
    "family": "context",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "values"
      },
      {
        "name": "example"
      }
    ],
    "portable": true
  },
  "formatNum": {
    "executionKind": "single",
    "family": "arithmetic",
    "fidelity": {
      "level": "deviation",
      "text": "Closes a negative pattern with its suffix, as SAP Cloud Integration does: (1,234.50). SAP PO leaves the closing parenthesis out."
    },
    "inputs": [
      {
        "name": "value"
      }
    ],
    "params": [
      {
        "default": "0",
        "name": "pattern",
        "syntax": "numberPattern",
        "type": "string"
      }
    ],
    "portable": true
  },
  "greater": {
    "executionKind": "single",
    "family": "arithmetic",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "a"
      },
      {
        "name": "b"
      }
    ],
    "portable": true
  },
  "if": {
    "executionKind": "single",
    "family": "boolean",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "condition"
      },
      {
        "name": "then"
      },
      {
        "name": "else"
      }
    ],
    "portable": true
  },
  "ifWithoutElse": {
    "executionKind": "context",
    "family": "boolean",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "condition"
      },
      {
        "name": "then"
      }
    ],
    "params": [
      {
        "default": "false",
        "name": "keepSuppressValues",
        "type": "string",
        "values": [
          "false",
          "true"
        ]
      }
    ],
    "portable": true
  },
  "index": {
    "executionKind": "queue",
    "family": "statistic",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "values"
      }
    ],
    "params": [
      {
        "default": "1",
        "name": "initial",
        "type": "string"
      },
      {
        "default": "1",
        "name": "increment",
        "type": "string"
      },
      {
        "default": "eachContext",
        "name": "reset",
        "type": "string",
        "values": [
          "eachContext",
          "never"
        ]
      }
    ],
    "portable": true
  },
  "indexOf": {
    "executionKind": "single",
    "family": "text",
    "fidelity": {
      "level": "deviation",
      "text": "Counts positions in Unicode characters (code points). SAP PO counts UTF-16 units, so positions after an emoji or another character beyond the Basic Multilingual Plane differ."
    },
    "inputs": [
      {
        "name": "value"
      },
      {
        "name": "search"
      }
    ],
    "portable": true
  },
  "inv": {
    "executionKind": "single",
    "family": "arithmetic",
    "fidelity": {
      "level": "deviation",
      "text": "Keeps a fixed number of decimal places (as in XPath). SAP PO keeps 17 significant digits."
    },
    "inputs": [
      {
        "name": "value"
      }
    ],
    "portable": true
  },
  "isNil": {
    "executionKind": "single",
    "family": "boolean",
    "fidelity": {
      "level": "deviation",
      "text": "Treats an empty element as nil. SAP PO treats only an element marked xsi:nil as nil."
    },
    "inputs": [
      {
        "name": "value"
      }
    ],
    "portable": true
  },
  "lastIndexOf": {
    "executionKind": "single",
    "family": "text",
    "fidelity": {
      "level": "deviation",
      "text": "Counts positions in Unicode characters (code points). SAP PO counts UTF-16 units, so positions after an emoji or another character beyond the Basic Multilingual Plane differ."
    },
    "inputs": [
      {
        "name": "value"
      },
      {
        "name": "search"
      }
    ],
    "portable": true
  },
  "length": {
    "executionKind": "single",
    "family": "text",
    "fidelity": {
      "level": "deviation",
      "text": "Counts Unicode characters (code points). SAP PO counts UTF-16 units, so an emoji or another character beyond the Basic Multilingual Plane counts as two there."
    },
    "inputs": [
      {
        "name": "value"
      }
    ],
    "portable": true
  },
  "less": {
    "executionKind": "single",
    "family": "arithmetic",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "a"
      },
      {
        "name": "b"
      }
    ],
    "portable": true
  },
  "mapWithDefault": {
    "executionKind": "context",
    "family": "context",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "queue"
      }
    ],
    "params": [
      {
        "default": "",
        "name": "default",
        "type": "string"
      }
    ],
    "portable": true
  },
  "max": {
    "executionKind": "single",
    "family": "arithmetic",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "a"
      },
      {
        "name": "b"
      }
    ],
    "portable": true
  },
  "min": {
    "executionKind": "single",
    "family": "arithmetic",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "a"
      },
      {
        "name": "b"
      }
    ],
    "portable": true
  },
  "multiply": {
    "executionKind": "single",
    "family": "arithmetic",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "a"
      },
      {
        "name": "b"
      }
    ],
    "portable": true
  },
  "neg": {
    "executionKind": "single",
    "family": "arithmetic",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "value"
      }
    ],
    "portable": true
  },
  "not": {
    "executionKind": "single",
    "family": "boolean",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "value"
      }
    ],
    "portable": true
  },
  "notEquals": {
    "executionKind": "single",
    "family": "boolean",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "a"
      },
      {
        "name": "b"
      }
    ],
    "portable": true
  },
  "or": {
    "executionKind": "single",
    "family": "boolean",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "a"
      },
      {
        "name": "b"
      }
    ],
    "portable": true
  },
  "power": {
    "executionKind": "single",
    "family": "arithmetic",
    "fidelity": {
      "level": "deviation",
      "text": "Exact for a whole exponent from 0 to 1000; any other exponent is computed in double precision. SAP PO computes in float or BigDecimal mode."
    },
    "inputs": [
      {
        "name": "base"
      },
      {
        "name": "exponent"
      }
    ],
    "portable": true
  },
  "removeContexts": {
    "executionKind": "queue",
    "family": "context",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "queue"
      }
    ],
    "portable": true
  },
  "replaceString": {
    "executionKind": "single",
    "family": "text",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "value"
      }
    ],
    "params": [
      {
        "default": "",
        "name": "search",
        "type": "string"
      },
      {
        "default": "",
        "name": "replacement",
        "type": "string"
      }
    ],
    "portable": true
  },
  "replaceValue": {
    "executionKind": "context",
    "family": "context",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "value"
      }
    ],
    "params": [
      {
        "default": "",
        "name": "replacement",
        "type": "string"
      }
    ],
    "portable": true
  },
  "round": {
    "executionKind": "single",
    "family": "arithmetic",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "value"
      }
    ],
    "portable": true
  },
  "sign": {
    "executionKind": "single",
    "family": "arithmetic",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "value"
      }
    ],
    "portable": true
  },
  "sort": {
    "executionKind": "context",
    "family": "context",
    "fidelity": {
      "level": "deviation",
      "text": "Sorts case-insensitively by Unicode character (code point). SAP PO puts 😀 before ﬀ and Ａ there, so text with an emoji or another character beyond the Basic Multilingual Plane can sort differently."
    },
    "inputs": [
      {
        "name": "queue"
      }
    ],
    "params": [
      {
        "default": "lexicographic",
        "name": "comparison",
        "type": "string",
        "values": [
          "lexicographic",
          "caseInsensitive",
          "numeric"
        ]
      },
      {
        "default": "ascending",
        "name": "order",
        "type": "string",
        "values": [
          "ascending",
          "descending"
        ]
      }
    ],
    "portable": true
  },
  "sortByKey": {
    "executionKind": "context",
    "family": "context",
    "fidelity": {
      "level": "deviation",
      "text": "Orders keys as \`sort\` does, case-insensitively by code point. SAP PO puts 😀 before ﬀ and Ａ, so keys with an emoji or another character beyond the Basic Multilingual Plane can sort differently."
    },
    "inputs": [
      {
        "name": "keys"
      },
      {
        "name": "values"
      }
    ],
    "params": [
      {
        "default": "lexicographic",
        "name": "comparison",
        "type": "string",
        "values": [
          "lexicographic",
          "caseInsensitive",
          "numeric"
        ]
      },
      {
        "default": "ascending",
        "name": "order",
        "type": "string",
        "values": [
          "ascending",
          "descending"
        ]
      }
    ],
    "portable": true
  },
  "splitByValue": {
    "executionKind": "context",
    "family": "context",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "queue"
      }
    ],
    "params": [
      {
        "default": "eachValue",
        "name": "mode",
        "type": "string",
        "values": [
          "eachValue",
          "valueChange",
          "emptyValue"
        ]
      }
    ],
    "portable": true
  },
  "sqr": {
    "executionKind": "single",
    "family": "arithmetic",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "value"
      }
    ],
    "portable": true
  },
  "sqrt": {
    "executionKind": "single",
    "family": "arithmetic",
    "fidelity": {
      "level": "deviation",
      "text": "Computed in double precision and returned as a decimal. SAP PO computes in float or BigDecimal mode."
    },
    "inputs": [
      {
        "name": "value"
      }
    ],
    "portable": true
  },
  "startsWith": {
    "executionKind": "single",
    "family": "text",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "value"
      },
      {
        "name": "prefix"
      }
    ],
    "portable": true
  },
  "substring": {
    "executionKind": "single",
    "family": "text",
    "fidelity": {
      "level": "deviation",
      "text": "Start and length count Unicode characters (code points). SAP PO counts UTF-16 units, so the cut differs after an emoji or another character beyond the Basic Multilingual Plane."
    },
    "inputs": [
      {
        "name": "value"
      }
    ],
    "params": [
      {
        "default": "0",
        "name": "start",
        "type": "string"
      },
      {
        "default": "0",
        "name": "length",
        "type": "string"
      }
    ],
    "portable": true
  },
  "subtract": {
    "executionKind": "single",
    "family": "arithmetic",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "a"
      },
      {
        "name": "b"
      }
    ],
    "portable": true
  },
  "sum": {
    "executionKind": "context",
    "family": "statistic",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "values"
      }
    ],
    "portable": true
  },
  "toLowerCase": {
    "executionKind": "single",
    "family": "text",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "value"
      }
    ],
    "portable": true
  },
  "toUpperCase": {
    "executionKind": "single",
    "family": "text",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "value"
      }
    ],
    "portable": true
  },
  "trim": {
    "executionKind": "single",
    "family": "text",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "value"
      }
    ],
    "portable": true
  },
  "useOneAsMany": {
    "executionKind": "queue",
    "family": "context",
    "fidelity": {
      "level": "exact"
    },
    "inputs": [
      {
        "name": "value"
      },
      {
        "name": "occurrences"
      },
      {
        "name": "contexts"
      }
    ],
    "portable": true
  }
}
`,a=20128,o=20128,s=`mappings/tree-20128`,c=`../structures/generated/tree-20128.xsd`,l=`structures/generated/tree-20128.xsd`,u=`urn:rail8:fake:tree`,d=[`Segment`,`Item`,`Partner`,`Address`,`Amount`,`Date`,`Text`,`Code`,`Qualifier`,`Reference`],f=[`id`,`type`,`unit`,`lang`,`currency`],p=[`xs:string`,`xs:decimal`,`xs:date`,`xs:integer`];function m(e){let t=e>>>0;return()=>{t=t+1831565813>>>0;let e=t;return e=Math.imul(e^e>>>15,e|1),e^=e+Math.imul(e^e>>>7,e|61),((e^e>>>14)>>>0)/4294967296}}function h(e,t){let n=m(e),r=e=>e[Math.floor(n()*e.length)],i={},a=[`t0`],o=t-1;for(let e=0;e<a.length;e++){let t=Math.min(o,3+Math.floor(n()*12)),s=Math.min(t-1,Math.floor(n()*3)),c=[];for(let i=0;i<t;i++){let l=i===t-1&&e===a.length-1&&o>t;if(i<s){let e=`${r(f)}${i}`;c.push(g(`attribute`,`@${e}`,e,n()<.5?0:1,1,r(p)))}else if(l||n()<.4){let e=`t${a.length}`;a.push(e),c.push({...g(`element`,`${r(d)}${i}`,``,0,void 0),type:e})}else c.push(g(`element`,`${r(d)}${i}`,``,n()<.5?0:1,1,r(p)))}i[a[e]]=c,o-=t}let s={...g(`element`,`Q{${u}}Tree`,`Tree`,1,1),namespace:u,type:`t0`};return{kind:`loaded`,fingerprint:`sha256:generated-${e}-${t}`,root:s,types:i}}function g(e,t,n,r,i,a){return{kind:e,step:t,namespace:``,localName:n||t,min:r,...i===void 0?{}:{max:i},...a===void 0?{}:{valueType:a},nillable:!1}}function _(e){let t=[],n=[],r=(i,a)=>{if(i.type===void 0)n.push(a);else{t.push(a);for(let t of e.types[i.type])r(t,`${a}/${t.step}`)}};return r(e.root,`/${e.root.step}`),{expandable:t,leaves:n}}function v(e,t){let n={bundle:[t],fingerprint:e.fingerprint,root:{element:e.root.step}},r={};return _(e).leaves.forEach((e,t)=>{r[e]={id:`t${t}`,result:`a${t}`,boxes:{[`a${t}`]:{kind:`source`,field:e}}}}),{formatVersion:1,source:n,target:{...n},targetFieldMappings:r}}var y=(e,t,n,r,i=!1)=>({kind:e.startsWith(`@`)?`attribute`:`element`,step:e,namespace:``,localName:e.replace(`@`,``),min:t,...n===void 0?{}:{max:n},valueType:r,nillable:i}),b={kind:`loaded`,fingerprint:`sha256:28ec3b615bbf0802cad0e676d1f5cf36d57f95216448a3653cac7b722b7ca87a`,root:{kind:`element`,step:`Q{urn:rail8:corpus:skeleton}Order`,namespace:`urn:rail8:corpus:skeleton`,localName:`Order`,min:1,max:1,nillable:!1,type:`/Q{urn:rail8:corpus:skeleton}Order`},types:{"/Q{urn:rail8:corpus:skeleton}Order":[y(`@channel`,0,1,`xs:string`),y(`OrderId`,1,1,`xs:string`),y(`Quantity`,0,1,`xs:integer`),y(`Amount`,0,1,`xs:decimal`,!0),y(`Urgent`,0,1,`xs:boolean`),y(`DeliveryDate`,0,1,`xs:date`),y(`PickupTime`,0,1,`xs:time`),y(`CreatedAt`,0,1,`xs:dateTime`),y(`Note`,0,void 0,`xs:string`)]}},x=(e,t)=>({code:`ambiguous-field-path`,side:`both`,file:`../structures/refused/same-step.xsd`,line:e,column:t}),S=(e,t,n)=>({kind:`element`,step:e,namespace:``,localName:e,min:t,max:1,valueType:`xs:string`,nillable:!1,...n&&{refusal:n}}),C={kind:`loaded`,fingerprint:`sha256:d26971c5a6f2df7fa0bf9f7287ae22f1e93ef12dd6ea4ac8ce748990ba0ec32e`,root:{kind:`element`,step:`Doc`,namespace:``,localName:`Doc`,min:1,max:1,nillable:!1,type:`/Doc`},types:{"/Doc":[S(`Id`,1),S(`Note`,0,x(14,65)),S(`Code`,0),S(`Note`,0,x(14,65)),{kind:`element`,step:`Head`,namespace:``,localName:`Head`,min:0,max:1,nillable:!1,type:`/Doc/Head`}],"/Doc/Head":[S(`Ref`,1,x(19,56)),S(`Ref`,1,x(19,56))]}},w=`urn:rail8:mapping:problem:`,T=JSON.parse(r),E;function D(){if(!E){let e=h(o,a);E={structure:e,mapping:v(e,c)}}return E}function O(){let e={...T};return delete e.targetFieldMappings,{"mappings/order-id-copy":T,"mappings/order-unmapped":e,[s]:D().mapping}}var k={"structures/skeleton/order.xsd":()=>b,"structures/refused/same-step.xsd":()=>C,[l]:()=>D().structure};function A(e,t){let n=t.map(t=>V(e,t)).join(`, `);return k[n]?.()??{kind:`refused`,diagnostics:[{severity:`error`,code:`schema-unreadable`,message:`the fake API has no structure for ${n}`,location:{kind:`file-position`,file:t.join(`, `),line:1,column:1}}]}}var j=`[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}`,M=RegExp(`^${j}(/${j}){0,15}$`),N=new Set([`test`,`compile`,`refresh`,`udfs`]);function P(e){return e.split(`/`).slice(0,-1).join(`/`)}var F=[{links:B(T),input:n,output:t}];function I(t=O(),n={}){let r=0,a=e=>({model:structuredClone(e),etag:`"fake:${++r}"`}),o=new Map(Object.entries(t).map(([e,t])=>[e,a(t)])),s=e=>{let t=o.get(e);if(!t)throw z(e);return t};return{listMappings:async()=>[...o].sort(([e],[t])=>e<t?-1:1).map(([e,{model:t}])=>L(e,t)),createMapping:async t=>{let{id:n,description:r}=t;if(!M.test(n)||N.has(n.slice(n.lastIndexOf(`/`)+1)))throw new e({type:`${w}invalid-workspace-path`,title:`Not a workspace path`,status:400,detail:`not a workspace path: "${n}"`});if(o.has(n))throw new e({type:`${w}mapping-exists`,title:`Mapping exists`,status:409,detail:`a mapping with this id exists`});let i=P(n),c={formatVersion:1,...r?{description:r}:{}},l=[];for(let e of[`source`,`target`]){let n=t[e];if(!n)continue;let r=A(i,n.bundle);r.kind===`refused`?l.push(...r.diagnostics):c[e]={bundle:n.bundle,fingerprint:r.fingerprint,root:n.root}}if(l.length>0)throw new e({type:`${w}model-refused`,title:`Model refused`,status:422,detail:`a structure of the new mapping cannot be loaded`,diagnostics:l});return o.set(n,a(H(c))),structuredClone(s(n))},getMapping:async e=>structuredClone(s(e)),saveMapping:async(t,n,r)=>{if(s(t).etag!==r)throw new e({type:`${w}mapping-changed`,title:`Mapping changed`,status:409,detail:`the mapping changed on disk since the If-Match tag was read; nothing was saved`});return o.set(t,a(H(n))),structuredClone(s(t))},loadStructure:async({base:e,bundle:t})=>A(e,t),runTest:async(t,{model:r,input:i})=>{let a=n.test?.[t];if(a)return structuredClone(a);let o=F.find(e=>e.input===i&&e.links===B(r));if(o)return{kind:`output`,output:o.output,diagnostics:[],trace:{}};let s=Object.values(r.targetFieldMappings??{}).filter(e=>e.result===null);if(s.length>0)return{kind:`refused`,diagnostics:s.map(e=>({severity:`error`,code:`unconnected-target-field`,message:`the target field mapping's result is not connected`,location:{kind:`authoring-id`,authoringId:e.id}}))};throw new e({type:`${w}invalid-request`,title:`Invalid request`,status:400,detail:`the fake API has no corpus case for this working copy and input`})},getCatalogue:async()=>JSON.parse(i),compile:async e=>(s(e),{diagnostics:structuredClone(n.compile?.[e]??[])}),refresh:async(e,t)=>{s(e);let r=P(e),i={...t},a=[];for(let e of[`source`,`target`]){let n=t[e];if(!n)continue;let o=A(r,n.bundle);o.kind===`loaded`?i[e]={...n,fingerprint:o.fingerprint}:a.push(...o.diagnostics)}return{model:H(i),diagnostics:a.length>0?a:structuredClone(n.refresh?.[e]??[])}}}}function L(e,t){let n=e.lastIndexOf(`/`);return{id:e,name:e.slice(n+1),package:e.slice(0,Math.max(n,0)),...t.description?{description:t.description}:{},...t.source?{source:R(t.source)}:{},...t.target?{target:R(t.target)}:{}}}function R({bundle:e,root:t}){return`element`in t?t.element.slice(t.element.indexOf(`}`)+1):`wsdl`in t?t.wsdl.operation:e[0].slice(e[0].lastIndexOf(`/`)+1)}function z(t){return new e({type:`${w}not-found`,title:`Not found`,status:404,detail:`no mapping ${t}`})}function B(e){return Object.entries(e.targetFieldMappings??{}).map(([e,t])=>{let n=t.result===null?void 0:t.boxes[t.result];return`${e}<-${n?.kind===`source`?n.field:`?`}`}).sort().join(`
`)}function V(e,t){let n=e===``?[]:e.split(`/`);for(let e of t.split(`/`))e===`..`?n.pop():n.push(e);return n.join(`/`)}function H(e){if(Array.isArray(e))return e.map(H);if(typeof e!=`object`||!e)return e;let t=e;return Object.fromEntries(Object.keys(t).sort().map(e=>[e,H(t[e])]))}export{I as fakeApiClient};