package io.flottila.mapping.runtime;

import io.flottila.mapping.ir.Name;
import io.flottila.mapping.xdm.QueueBlock;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * The projection of an XML input, in one StAX pass (ADR-0004): only the element steps the queues
 * name are followed, every other subtree is skipped, and no tree is built. A context opens at each
 * start of its context field, and a value is appended at each occurrence of its field: the text of
 * a leaf element, with references and CDATA resolved, {@code ""} for a structure field (ADR-0008),
 * or an attribute's value, which its element's start tag holds or lacks (ADR-0006 since #102). The
 * input is still parsed whole, so it must be well-formed throughout. A DOCTYPE is refused, and no
 * DTD or external entity is ever read.
 *
 * <p>The step trie is built once at compile and only read by a run.
 */
final class XmlProjection implements Projection {

  /**
   * An attribute's queue on its element's step: the attribute's name, whose namespace is {@code ""}
   * for none, as the reader's names have it, and the queue's index.
   */
  private record AttributeQueue(QName name, int queue) {}

  /** A node of the trie of element steps. */
  private static final class Step {
    private final String namespace;
    private final String localName;
    private Step[] children = new Step[0];

    /** The queues whose context field this step is: each occurrence opens a context. */
    private int[] contexts = new int[0];

    /** The queues whose leaf field this step is: each occurrence appends its text. */
    private int[] values = new int[0];

    /**
     * The queues whose structure field this step is: each occurrence appends {@code ""}, and its
     * subtree is still walked for the queues below it.
     */
    private int[] occurrences = new int[0];

    /**
     * The queues of this step's attributes: each occurrence whose start tag holds the attribute
     * appends its value, and one that lacks it appends nothing.
     */
    private AttributeQueue[] attributes = new AttributeQueue[0];

    Step(String namespace, String localName) {
      this.namespace = namespace;
      this.localName = localName;
    }

    /** The child step of that name, or {@code null}. */
    Step child(String namespace, String localName) {
      for (Step child : children) {
        if (child.localName.equals(localName) && child.namespace.equals(namespace)) {
          return child;
        }
      }
      return null;
    }

    Step childOrAdd(Name name) {
      Step child = child(name.namespace(), name.localName());
      if (child == null) {
        child = new Step(name.namespace(), name.localName());
        children = Arrays.copyOf(children, children.length + 1);
        children[children.length - 1] = child;
      }
      return child;
    }
  }

  /** Above the root: its one child is the source root. */
  private final Step document = new Step("", "");

  /** What a skipped subtree's elements stand on: it has no children. */
  private final Step skipped = new Step("", "");

  private final int queues;

  /**
   * @param plan the queues, by their index in the frame
   */
  XmlProjection(List<Queue> plan) {
    queues = plan.size();
    for (int k = 0; k < queues; k++) {
      Queue queue = plan.get(k);
      List<Name> field = queue.field();
      // an attribute's queue sits on its element's step: its last step is no element step
      int elements = queue.attribute() ? field.size() - 1 : field.size();
      Step step = document;
      for (int depth = 1; depth <= elements; depth++) {
        step = step.childOrAdd(field.get(depth - 1));
        if (depth == queue.contextDepth()) {
          step.contexts = Projection.append(step.contexts, k);
        }
      }
      if (queue.attribute()) {
        step.attributes = Arrays.copyOf(step.attributes, step.attributes.length + 1);
        Name name = field.getLast();
        step.attributes[step.attributes.length - 1] =
            new AttributeQueue(new QName(name.namespace(), name.localName()), k);
      } else if (queue.structureField()) {
        step.occurrences = Projection.append(step.occurrences, k);
      } else {
        step.values = Projection.append(step.values, k);
      }
    }
  }

  /**
   * {@inheritDoc}
   *
   * @throws MalformedInputException when the input is no well-formed XML document, or has a DOCTYPE
   */
  @Override
  public QueueBlock[] read(InputStream in) throws MalformedInputException {
    QueueBlock.Builder[] builders = Projection.builders(queues);
    XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    try {
      XMLStreamReader reader = factory.createXMLStreamReader(in);
      try {
        walk(reader, builders);
      } finally {
        reader.close();
      }
    } catch (XMLStreamException e) {
      throw new MalformedInputException(e.getMessage(), e);
    }
    return Projection.blocks(builders);
  }

  private void walk(XMLStreamReader reader, QueueBlock.Builder[] builders)
      throws XMLStreamException, MalformedInputException {
    ArrayDeque<Step> open = new ArrayDeque<>();
    open.push(document);
    while (reader.hasNext()) {
      // checked per event, so an interrupt stops the parse before the input is read to its end
      if (Thread.currentThread().isInterrupted()) {
        throw new CancellationException("the mapping run was interrupted while parsing its input");
      }
      switch (reader.next()) {
        case XMLStreamConstants.START_ELEMENT -> {
          Step step = open.peek().child(namespace(reader), reader.getLocalName());
          if (step == null) {
            open.push(skipped);
          } else {
            for (int k : step.contexts) {
              builders[k].newContext();
            }
            for (int k : step.occurrences) {
              builders[k].append("");
            }
            // read before getElementText, which moves the reader past the start tag
            for (AttributeQueue attribute : step.attributes) {
              attribute(reader, attribute, builders[attribute.queue()]);
            }
            if (step.values.length == 0) {
              open.push(step);
            } else {
              // reads up to the element's end tag, which is therefore never seen here
              String value = reader.getElementText();
              for (int k : step.values) {
                builders[k].append(value);
              }
            }
          }
        }
        case XMLStreamConstants.END_ELEMENT -> open.pop();
        case XMLStreamConstants.DTD ->
            // the JDK reader reports a DOCTYPE even with SUPPORT_DTD off (observed)
            throw new MalformedInputException("the input has a DOCTYPE; no DTD is read", null);
        default -> {
          // text between fields, comments, processing instructions and the end of the document
        }
      }
    }
  }

  /**
   * Appends to {@code queue} the value of the start tag's attribute that {@code attribute} names,
   * if the tag holds it; a tag that lacks it appends nothing (#102).
   */
  private static void attribute(
      XMLStreamReader reader, AttributeQueue attribute, QueueBlock.Builder queue) {
    // QName equality is local name and namespace, a null namespace read as "" (javap); well-formed
    // input holds an attribute once per start tag, so at most one index matches
    for (int i = 0; i < reader.getAttributeCount(); i++) {
      if (reader.getAttributeName(i).equals(attribute.name())) {
        queue.append(reader.getAttributeValue(i));
      }
    }
  }

  /** The element's namespace; the reader gives {@code null} for none (observed). */
  private static String namespace(XMLStreamReader reader) {
    String namespace = reader.getNamespaceURI();
    return namespace == null ? "" : namespace;
  }
}
