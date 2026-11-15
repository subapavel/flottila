package dev.rail8.mapping.mapping;

import dev.rail8.mapping.ir.AuthoringId;

/**
 * An authoring element, as stage 2 reports it: the consuming box, or the target field mapping for
 * its {@code result}.
 *
 * @param id the element's authoring id
 */
public record AtAuthoringId(AuthoringId id) implements Location {}
