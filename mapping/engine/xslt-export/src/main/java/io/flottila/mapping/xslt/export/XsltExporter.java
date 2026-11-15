package io.flottila.mapping.xslt.export;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.flottila.mapping.ir.AtomicToQueue;
import io.flottila.mapping.ir.AttributeConstructor;
import io.flottila.mapping.ir.ElementConstructor;
import io.flottila.mapping.ir.Expr;
import io.flottila.mapping.ir.FunCall;
import io.flottila.mapping.ir.InputFormat;
import io.flottila.mapping.ir.Lift;
import io.flottila.mapping.ir.Literal;
import io.flottila.mapping.ir.Name;
import io.flottila.mapping.ir.SourceQueue;
import io.flottila.mapping.ir.TargetDocument;
import io.flottila.mapping.ir.TargetRepeat;
import io.flottila.mapping.ir.TargetSlots;
import io.flottila.mapping.ir.TextConstructor;
import io.flottila.mapping.ir.VarRef;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The portable XSLT 3.0 export of unoptimised IR (ADR-0023): one stylesheet that imports the helper
 * module from its own directory. Each instruction and literal element starts a line of its own, two
 * spaces deeper than its parent. An element whose content is a single text keeps its {@code
 * xsl:value-of} and end tag on its own line; any other element's end tag starts a line.
 *
 * <ul>
 *   <li>Every (prefix, URI) pair is declared once, on {@code xsl:stylesheet}, under {@code
 *       exclude-result-prefixes="#all"}: first the stylesheet's own (XSLT, XML Schema, arrays and
 *       the helper's {@code po}), then the source namespaces as {@code s0}, {@code s1} … in IR walk
 *       order, then the IR's output prefixes (ADR-0022) in the order first met, the root's first. A
 *       pair met again is not declared again.
 *   <li>A name without a namespace stays bare. There is never a default namespace and never an
 *       {@code xpath-default-namespace}.
 *   <li>{@code TargetSlots} is an {@code xsl:for-each} over {@code po:slots} of its queue, its
 *       parent's slot number and its {@code max}, {@code ()} when unbounded, inside {@code
 *       po:at-least} with its {@code min} and the id of its target field mapping, its origin, when
 *       the min is above 0 (ADR-0006 since #52). Each item is {@code [slot number, value]}, bound
 *       to its slot-number and value variables.
 *   <li>{@code TargetRepeat} is an {@code xsl:for-each} over {@code 1 to} its copies, which binds
 *       its slot-number variable to {@code ($p - 1) * copies + .}, where {@code $p} is its parent's
 *       slot number (ADR-0006; #63). A generating structure field's single value follows as {@code
 *       <xsl:value-of select="substring(value, 1, 0)"/>}, a zero-length text that evaluates it
 *       before the body (ADR-0006 as amended by #83). A generating block, a value with a value
 *       variable, follows instead as an {@code xsl:for-each} over {@code po:generated} of its queue
 *       variable, which binds the value variable to its item around the body, so a copy whose
 *       block's first slot is SUPPRESS or missing writes nothing (#217, F-46).
 *   <li>An {@code AttributeConstructor} is {@code <xsl:attribute name="name" select="value"/>}, its
 *       name under the IR's output prefix (ADR-0022), inside its item's {@code xsl:for-each}
 *       (ADR-0006 since #102).
 *   <li>Each back end evaluates a {@code TargetSlots} queue at most once per mapping run (ADR-0006
 *       since #52). A {@code TargetSlots} directly under the root is written outside any {@code
 *       xsl:for-each}, so its {@code po:slots} reads its queue in place. Inside an occurrence,
 *       where the {@code xsl:for-each} runs once per occurrence of the parent, the queue is bound
 *       once instead, as {@code po:queue1}, {@code po:queue2} … in IR walk order at the top of the
 *       template, and {@code po:slots} reads the variable. A generating block is always bound so,
 *       under the root too, as each of its copies reads it (#217).
 *   <li>A queue is an {@code array(item()*)} with one member per context, each value an {@code
 *       xs:string} or {@code $po:SUPPRESS}. A {@code SourceQueue} reads the source document as
 *       {@code /}, the template's focus wherever a queue is written, and has at least one context
 *       ({@code po:source-queue}, F-17); a leaf field's member holds its {@code string()} per
 *       occurrence, a structure field's {@code ''} (ADR-0008; #63), and an attribute's, whose last
 *       step is {@code @name}, its value's {@code string()} (#102). A {@code FunCall} is {@code
 *       po:<catalogue name>} of the helper module over its args in {@code FunCall} order
 *       (ADR-0025), then the id of its function box, its origin, where the helper's declaration
 *       ends in the param {@code box}: a function that can raise a data error (ADR-0026). A {@code
 *       Literal} is an XPath string literal in apostrophes, a table param's text included
 *       (ADR-0026). An {@code AtomicToQueue} is {@code [(value)]}, one context that holds its value
 *       (ADR-0006 since #83).
 *   <li>The export of a JSON input (ADR-0008 "JSON as a source at run time", #98) also declares
 *       {@code fn}, the namespace of the XML representation of JSON, after {@code po}. It is
 *       started at its initial template with the param {@code po:input}, the URI of the JSON text,
 *       and applies its templates to {@code json-to-xml(unparsed-text($po:input), map{'duplicates':
 *       'reject'})}, so the source document is that representation. Its {@code $} step is the
 *       representation's top item, each further step the item whose {@code @key} is its property
 *       name: a leaf any scalar, any other field an object, and an array property each item of its
 *       {@code fn:array}.
 *   <li>A {@code Lift} is {@code po:lift} over the sequence of its queues and an inline function of
 *       {@code $in as xs:string*}, which binds slot variable k to {@code $in[k]} and returns the
 *       body (ADR-0025 since #51).
 *   <li>An expression is escaped for the attribute it stands in, so a literal keeps every
 *       character, white space included.
 * </ul>
 *
 * <p>An IR it cannot write is a compiler bug, as in {@code IrVerifier}, so it throws {@link
 * IllegalArgumentException}: a namespace under prefix {@code ""} or a prefix without a namespace,
 * one prefix bound to two URIs, a node where it cannot stand, or a {@code FunCall} of a function
 * the helper module does not declare, or declares with another number of params. It never returns
 * part of a stylesheet.
 */
public final class XsltExporter {

  /** The file name of the helper module every export imports (ADR-0023). */
  public static final String HELPER_MODULE = "po-runtime.xsl";

  /**
   * The stylesheet: its declarations, the helper module, the root's slot-number variable, the queue
   * variables, the root's name and its content. Each line of the queue variables and of the content
   * starts with its own line break.
   */
  private static final String STYLESHEET =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <xsl:stylesheet version="3.0"%1$s exclude-result-prefixes="#all">
        <xsl:import href="%2$s"/>
        <xsl:output method="xml" version="1.0" encoding="UTF-8" indent="no" \
      omit-xml-declaration="no"/>%7$s
        <xsl:template match="/">
          <xsl:variable name="%3$s" as="xs:integer" select="1"/>%4$s
          <%5$s>%6$s
          </%5$s>
        </xsl:template>
      </xsl:stylesheet>
      """;

  /**
   * The entry of a JSON input's export (ADR-0008 "JSON as a source at run time", #98): the param
   * {@code po:input}, the URI of the JSON text, and the initial template, which applies the
   * templates to its XML representation; an object with a key twice is the error FOJS0003.
   */
  private static final String JSON_ENTRY =
      """

        <xsl:param name="po:input" as="xs:string" required="yes"/>
        <xsl:template name="xsl:initial-template">
          <xsl:apply-templates select="json-to-xml(unparsed-text($po:input), \
      map{'duplicates': 'reject'})"/>
        </xsl:template>""";

  /** The namespace of the XML representation of JSON, which {@code json-to-xml} gives. */
  private static final String JSON_NAMESPACE = "http://www.w3.org/2005/xpath-functions";

  /** A JSON leaf's item in the XML representation: any scalar, {@code null} included. */
  private static final String JSON_SCALAR = "(fn:string|fn:number|fn:boolean|fn:null)";

  /** A JSON object's item in the XML representation. */
  private static final String JSON_OBJECT = "fn:map";

  /** A namespace declaration on {@code xsl:stylesheet}, with the space before it. */
  private static final String DECLARATION = " xmlns:%s=\"%s\"";

  private static final String FOR_EACH =
      """
      <xsl:for-each select="%s">""";

  /** The occurrences of a {@code TargetSlots}: its queue, its parent's slot number, its max. */
  private static final String SLOTS = "po:slots(%s, %s, %s)";

  /**
   * The occurrences of a {@code TargetSlots} whose min is above 0, the min, and its target field
   * mapping, which the error names.
   */
  private static final String AT_LEAST = "po:at-least(%s, %d, %s)";

  private static final String SLOT_VARIABLE =
      """
      <xsl:variable name="%s" as="xs:integer" select="?1"/>""";

  private static final String VALUE_VARIABLE =
      """
      <xsl:variable name="%s" as="xs:string" select="?2"/>""";

  private static final String END_FOR_EACH =
      """
      </xsl:for-each>""";

  private static final String VALUE_OF =
      """
      <xsl:value-of select="%s"/>""";

  /** An {@code AttributeConstructor} (#102): its output name and its value. */
  private static final String ATTRIBUTE =
      """
      <xsl:attribute name="%s" select="%s"/>""";

  /**
   * The variable that binds the queue of a {@code TargetSlots} inside an occurrence once, at the
   * top of the template, where the focus is the source document: its name and its queue.
   */
  private static final String QUEUE_VARIABLE =
      """
      <xsl:variable name="%s" as="array(item()*)" select="%s"/>""";

  /** The name of the n-th queue variable, in the po namespace, where no IR variable name is. */
  private static final String QUEUE_NAME = "po:queue%d";

  /**
   * A {@code SourceQueue}: its context's path from the root, its field's path under it, and what
   * each occurrence gives. {@code po:source-queue} makes it one empty context when the context
   * never occurs (ADR-0004 as amended, F-17).
   */
  private static final String QUEUE =
      """
      po:source-queue(array:join(/%s ! [%s ! %s]))""";

  /** What an occurrence of a leaf field gives in its queue: its text. */
  private static final String LEAF_VALUE = "string()";

  /** What an occurrence of a structure field gives in its queue (ADR-0008). */
  private static final String STRUCTURE_VALUE = "''";

  /** The occurrences of a {@code TargetRepeat}: its copies. */
  private static final String REPEAT = "1 to %d";

  /**
   * A generating structure field's value, which each copy of its {@code TargetRepeat} evaluates and
   * writes as no text (ADR-0006 as amended by #83).
   */
  private static final String REPEAT_VALUE = "substring(%s, 1, 0)";

  /** The slot number of a {@code TargetRepeat}'s occurrence: its name, parent slot, copies. */
  private static final String REPEAT_SLOT_VARIABLE =
      """
      <xsl:variable name="%s" as="xs:integer" select="(%s - 1) * %d + ."/>""";

  /**
   * The value a copy of a generating block writes, its queue variable's first slot, or none when
   * that slot is SUPPRESS or missing, so the copy writes nothing (#217, F-46).
   */
  private static final String GENERATED = "po:generated(%s)";

  /** The value variable of a generating block's copy, the item {@link #GENERATED} gives. */
  private static final String GENERATED_VARIABLE =
      """
      <xsl:variable name="%s" as="xs:string" select="."/>""";

  /**
   * A {@code Lift} (ADR-0025): {@code po:lift} over its queues, and over the inline function that
   * binds the slot variables and returns the body.
   */
  private static final String LIFT =
      """
      po:lift((%s), function($in as xs:string*) as xs:string { let %s return %s })""";

  /** An {@code AtomicToQueue}: one context that holds its value (ADR-0006 since #83). */
  private static final String ATOMIC_TO_QUEUE = "[(%s)]";

  /** The {@code max} of a {@code TargetSlots} that is unbounded. */
  private static final String UNBOUNDED = "()";

  /** The indent of one level. */
  private static final String INDENT = "  ";

  /** The depth of the template's content: stylesheet, template. */
  private static final int TEMPLATE_DEPTH = 2;

  /** The depth of the root's content: stylesheet, template, root. */
  private static final int CONTENT_DEPTH = 3;

  /** The last param of a helper function that can raise a data error: its box's id (ADR-0026). */
  private static final String BOX = "box";

  /**
   * A function of the helper module: the number of its own params, and whether the last is {@link
   * #BOX}.
   */
  record Signature(int params, boolean endsInBox) {}

  /**
   * An {@code xsl:function} start tag of the helper module, its name, and its params: the {@code
   * xsl:param} empty-element tags that follow it.
   */
  private static final Pattern FUNCTION =
      Pattern.compile(
          "<xsl:function\\s[^>]*?\\bname=\"([^\"]*)\"[^>]*>((?:\\s*<xsl:param\\s[^>]*/>)*)");

  /** The name of one {@code xsl:param} empty-element tag. */
  private static final Pattern PARAM = Pattern.compile("<xsl:param\\s[^>]*?\\bname=\"([^\"]*)\"");

  /** The helper module's functions, by name, {@code po:<catalogue name>} for a catalogue's. */
  private static final Map<String, Signature> SIGNATURES = signatures(helperModule());

  /** Every prefix bound so far, to its URI. */
  private final Map<String, String> bound = new HashMap<>();

  /** The prefix of each source namespace met so far. */
  private final Map<String, String> sourcePrefixes = new HashMap<>();

  /** The declarations of the stylesheet's own namespaces, then of the source namespaces. */
  private final StringBuilder declarations = new StringBuilder();

  /** The declarations of the IR's output prefixes. */
  private final StringBuilder outputDeclarations = new StringBuilder();

  /**
   * The queue variables, one per {@code TargetSlots} inside an occurrence and per generating block,
   * in IR walk order.
   */
  private final StringBuilder queueVariables = new StringBuilder();

  /** The number of queue variables so far. */
  private int queueCount;

  /** The input format, which a {@code SourceQueue}'s path follows. */
  private final InputFormat input;

  /** What the stylesheet holds between its output and its template: a JSON input's entry. */
  private final String entry;

  private XsltExporter(InputFormat input) {
    this.input = input;
    String stylesheet = "xsl:stylesheet";
    bind("xsl", "http://www.w3.org/1999/XSL/Transform", declarations, stylesheet);
    bind("xs", "http://www.w3.org/2001/XMLSchema", declarations, stylesheet);
    bind("array", "http://www.w3.org/2005/xpath-functions/array", declarations, stylesheet);
    bind("po", "urn:flottila:mapping:po", declarations, stylesheet);
    entry =
        switch (input) {
          case XML -> "";
          case JSON -> {
            bind("fn", JSON_NAMESPACE, declarations, stylesheet);
            yield JSON_ENTRY;
          }
        };
  }

  /**
   * The stylesheet of {@code document}, which the IR verifier has accepted.
   *
   * @throws IllegalArgumentException if the exporter cannot write {@code document}
   */
  public static String export(TargetDocument document) {
    XsltExporter exporter = new XsltExporter(document.input());
    String root = exporter.outputName(document.prefix(), document.root(), "the root");
    StringBuilder content = new StringBuilder();
    for (Expr expr : document.content()) {
      exporter.instruction(expr, CONTENT_DEPTH, false, content);
    }
    return STYLESHEET.formatted(
        exporter.declarations.toString() + exporter.outputDeclarations,
        HELPER_MODULE,
        document.rootSlot(),
        exporter.queueVariables,
        root,
        content,
        exporter.entry);
  }

  /** The text of the helper module, the resource {@link #HELPER_MODULE} of this package. */
  public static String helperModule() {
    try (InputStream in = XsltExporter.class.getResourceAsStream(HELPER_MODULE)) {
      return new String(in.readAllBytes(), UTF_8);
    } catch (IOException e) {
      // the resource lies in this module's own jar or directory: only a broken build fails here
      throw new UncheckedIOException(e);
    }
  }

  /**
   * The signature of each {@code xsl:function} of {@code helper}: the {@code xsl:param} elements
   * right after its start tag, its own, so a param of an {@code xsl:iterate} in its body is none of
   * them. In XSLT 3.0 a function's param is empty (XTSE0760), so it is written as an empty-element
   * tag.
   */
  static Map<String, Signature> signatures(String helper) {
    Map<String, Signature> signatures = new HashMap<>();
    Matcher function = FUNCTION.matcher(helper);
    while (function.find()) {
      int params = 0;
      String last = "";
      Matcher param = PARAM.matcher(function.group(2));
      while (param.find()) {
        params++;
        last = param.group(1);
      }
      signatures.put(function.group(1), new Signature(params, last.equals(BOX)));
    }
    return Map.copyOf(signatures);
  }

  /**
   * Appends {@code expr} as the instructions that create its content, at {@code depth}.
   *
   * @param inOccurrence whether {@code expr} stands inside an {@code xsl:for-each}, which runs once
   *     per occurrence
   */
  private void instruction(Expr expr, int depth, boolean inOccurrence, StringBuilder out) {
    switch (expr) {
      case TargetSlots s -> {
        String queue = expression(s.queue());
        if (inOccurrence) {
          // written here, the queue would be evaluated once per parent occurrence (ADR-0006)
          String name = QUEUE_NAME.formatted(++queueCount);
          line(queueVariables, TEMPLATE_DEPTH, QUEUE_VARIABLE.formatted(name, attribute(queue)));
          queue = "$" + name;
        }
        String slots = SLOTS.formatted(queue, expression(s.parentSlot()), max(s.max()));
        String occurrences =
            s.min() > 0 ? AT_LEAST.formatted(slots, s.min(), literal(s.origin().value())) : slots;
        line(out, depth, FOR_EACH.formatted(attribute(occurrences)));
        line(out, depth + 1, SLOT_VARIABLE.formatted(s.slotVar()));
        line(out, depth + 1, VALUE_VARIABLE.formatted(s.valueVar()));
        instruction(s.body(), depth + 1, true, out);
        line(out, depth, END_FOR_EACH);
      }
      case TargetRepeat r -> {
        line(out, depth, FOR_EACH.formatted(REPEAT.formatted(r.copies())));
        line(
            out,
            depth + 1,
            REPEAT_SLOT_VARIABLE.formatted(
                r.slotVar(), attribute(expression(r.parentSlot())), r.copies()));
        if (r.valueVar().isPresent()) {
          // a generating block (#217): bound once, under the root too, as every copy reads it
          String name = QUEUE_NAME.formatted(++queueCount);
          line(
              queueVariables,
              TEMPLATE_DEPTH,
              QUEUE_VARIABLE.formatted(name, attribute(expression(r.value().orElseThrow()))));
          line(out, depth + 1, FOR_EACH.formatted(GENERATED.formatted("$" + name)));
          line(out, depth + 2, GENERATED_VARIABLE.formatted(r.valueVar().get()));
          instruction(r.body(), depth + 2, true, out);
          line(out, depth + 1, END_FOR_EACH);
        } else {
          r.value()
              .ifPresent(
                  value ->
                      line(
                          out,
                          depth + 1,
                          VALUE_OF.formatted(
                              attribute(REPEAT_VALUE.formatted(expression(value))))));
          instruction(r.body(), depth + 1, true, out);
        }
        line(out, depth, END_FOR_EACH);
      }
      case ElementConstructor e -> {
        String name =
            outputName(e.prefix(), e.name(), "ElementConstructor of " + e.origin().value());
        line(out, depth, "<" + name + ">");
        String end = "</" + name + ">";
        if (e.content().size() == 1 && e.content().getFirst() instanceof TextConstructor t) {
          // a single text stays on its element's line; any other content, a text after attribute
          // items included (#102), starts a line of its own, and so does the end tag after it
          out.append(valueOf(t)).append(end);
        } else {
          for (Expr content : e.content()) {
            instruction(content, depth + 1, inOccurrence, out);
          }
          line(out, depth, end);
        }
      }
      case AttributeConstructor a ->
          line(
              out,
              depth,
              ATTRIBUTE.formatted(
                  outputName(a.prefix(), a.name(), "AttributeConstructor of " + a.origin().value()),
                  attribute(expression(a.value()))));
      case TextConstructor t -> line(out, depth, valueOf(t));
      case SourceQueue q -> throw misplaced(q, "content");
      case VarRef v -> throw misplaced(v, "content");
      case Literal l -> throw misplaced(l, "content");
      case FunCall c -> throw misplaced(c, "content");
      case Lift l -> throw misplaced(l, "content");
      case AtomicToQueue a -> throw misplaced(a, "content");
    }
  }

  /** The {@code xsl:value-of} of {@code t}'s value. */
  private String valueOf(TextConstructor t) {
    return VALUE_OF.formatted(attribute(expression(t.value())));
  }

  /** {@code value} as an XPath string literal in apostrophes, an apostrophe doubled. */
  private static String literal(String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  /** {@code expr} as an XPath expression. */
  private String expression(Expr expr) {
    return switch (expr) {
      case SourceQueue q -> {
        String where = "SourceQueue of " + q.origin().value();
        yield QUEUE.formatted(
            path(q, 0, q.contextDepth(), where),
            path(q, q.contextDepth(), q.field().size(), where),
            q.structureField() ? STRUCTURE_VALUE : LEAF_VALUE);
      }
      case VarRef v -> "$" + v.name();
      case Literal l -> literal(l.value());
      case FunCall c -> call(c);
      case Lift l -> lift(l);
      case TargetSlots s -> throw misplaced(s, "an expression");
      case TargetRepeat r -> throw misplaced(r, "an expression");
      case ElementConstructor e -> throw misplaced(e, "an expression");
      case AttributeConstructor a -> throw misplaced(a, "an expression");
      case TextConstructor t -> throw misplaced(t, "an expression");
      case AtomicToQueue a -> ATOMIC_TO_QUEUE.formatted(expression(a.value()));
    };
  }

  /**
   * {@code c} as {@code po:<catalogue name>} over its args in {@code FunCall} order (ADR-0025), and
   * the id of its function box last where the helper's declaration ends in {@link #BOX} (ADR-0026).
   */
  private String call(FunCall c) {
    String name = "po:" + c.function().name();
    Signature signature = SIGNATURES.get(name);
    if (signature == null) {
      throw new IllegalArgumentException(c + ": the helper module has no function " + name);
    }
    List<String> args = new ArrayList<>();
    for (Expr arg : c.args()) {
      args.add(expression(arg));
    }
    if (signature.endsInBox()) {
      args.add(literal(c.origin().value()));
    }
    if (args.size() != signature.params()) {
      throw new IllegalArgumentException(
          c + ": " + name + " of the helper module takes " + signature.params() + " args");
    }
    return name + "(" + String.join(", ", args) + ")";
  }

  /**
   * {@code l} as {@code po:lift} over the sequence of its queues and an inline function, whose
   * {@code let} binds slot variable k to the k-th string it gets, and which returns the body.
   */
  private String lift(Lift l) {
    List<String> bindings = new ArrayList<>();
    for (int k = 0; k < l.slotVars().size(); k++) {
      bindings.add("$" + l.slotVars().get(k) + " := $in[" + (k + 1) + "]");
    }
    return LIFT.formatted(
        l.queues().stream().map(this::expression).collect(Collectors.joining(", ")),
        String.join(", ", bindings),
        expression(l.body()));
  }

  private static String max(long max) {
    return max == Long.MAX_VALUE ? UNBOUNDED : Long.toString(max);
  }

  /**
   * The relative path of the steps of {@code q} after depth {@code from} up to depth {@code to}.
   *
   * <p>Over XML each is an element step under its source prefix, and the field's last is an
   * attribute step when {@code q} reads an attribute (#102). Over JSON each is a JSON step.
   */
  private String path(SourceQueue q, int from, int to, String where) {
    List<String> names = new ArrayList<>();
    for (int depth = from + 1; depth <= to; depth++) {
      Name step = q.field().get(depth - 1);
      boolean last = depth == q.field().size();
      names.add(
          switch (input) {
            case XML -> (last && q.attribute() ? "@" : "") + sourceName(step, where);
            case JSON ->
                jsonStep(step, depth, last && !q.structureField(), q.arrays().contains(depth));
          });
    }
    return String.join("/", names);
  }

  /**
   * A JSON step at {@code depth} in the XML representation of JSON (ADR-0008, #98): the root's item
   * has no key, any other its property name as {@code @key}; a leaf's item is any scalar, any
   * other's an object, and an array property's are the items of its array. A value of another kind
   * matches nothing, so it reads as absent, as in the runtime.
   */
  private static String jsonStep(Name step, int depth, boolean leaf, boolean array) {
    String key = depth == 1 ? "" : "[@key=" + literal(step.localName()) + "]";
    String item = leaf ? JSON_SCALAR : JSON_OBJECT;
    return array ? "fn:array" + key + "/" + item : item + key;
  }

  /** The name test of a source step: bare without a namespace, else {@code s0}, {@code s1} … */
  private String sourceName(Name step, String where) {
    String namespace = step.namespace();
    if (namespace.isEmpty()) {
      return step.localName();
    }
    String prefix = sourcePrefixes.get(namespace);
    if (prefix == null) {
      prefix = "s" + sourcePrefixes.size();
      sourcePrefixes.put(namespace, prefix);
      bind(prefix, namespace, declarations, where);
    }
    return prefix + ":" + step.localName();
  }

  /** The name of an output element under the IR's {@code prefix} (ADR-0022). */
  private String outputName(String prefix, Name name, String where) {
    if (prefix.isEmpty() != name.namespace().isEmpty()) {
      throw new IllegalArgumentException(
          where
              + ": "
              + name
              + " under prefix \""
              + prefix
              + "\"; a name has a prefix exactly when it has a namespace (ADR-0022)");
    }
    if (prefix.isEmpty()) {
      return name.localName();
    }
    bind(prefix, name.namespace(), outputDeclarations, where);
    return prefix + ":" + name.localName();
  }

  /**
   * Binds {@code prefix} to {@code uri}, and appends its declaration to {@code into} if it is new.
   */
  private void bind(String prefix, String uri, StringBuilder into, String where) {
    String was = bound.putIfAbsent(prefix, uri);
    if (was == null) {
      into.append(DECLARATION.formatted(prefix, attribute(uri)));
    } else if (!was.equals(uri)) {
      throw new IllegalArgumentException(
          where + ": prefix " + prefix + " is bound to " + was + " and to " + uri);
    }
  }

  /**
   * {@code value} escaped for an attribute in quotes, white space included, which a parser would
   * otherwise normalise (XML 1.0 §3.3.3).
   */
  private static String attribute(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace("\"", "&quot;")
        .replace("\t", "&#9;")
        .replace("\n", "&#10;")
        .replace("\r", "&#13;");
  }

  private static void line(StringBuilder out, int depth, String text) {
    out.append('\n').append(INDENT.repeat(depth)).append(text);
  }

  /**
   * A node where the IR's shape has no place for it; the IR verifier does not check places. The
   * node's text names its origin.
   */
  private static IllegalArgumentException misplaced(Expr expr, String place) {
    return new IllegalArgumentException(expr + " stands in " + place);
  }
}
