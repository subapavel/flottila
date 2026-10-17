package io.flottila.core;

import net.sf.saxon.s9api.XdmNode;
import java.util.Map;

/** Convenience contract: the platform parses/serialises, you only see the tree. */
@FunctionalInterface
public interface XmlStep {
    XdmNode apply(XdmNode in, Map<String, String> headers) throws Exception;
}
