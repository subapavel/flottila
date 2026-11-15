package io.flottila.mapping.mapping;

import io.flottila.mapping.ir.AuthoringId;

/**
 * An authoring element, as stage 2 reports it: the consuming box, or the target field mapping for
 * its {@code result}.
 *
 * @param id the element's authoring id
 */
public record AtAuthoringId(AuthoringId id) implements Location {}
