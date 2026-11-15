package io.flottila.mapping.service;

import org.eclipse.microprofile.openapi.annotations.media.DiscriminatorMapping;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The result of loading a structure (ADR-0015, ADR-0024): its graph and fingerprint, or the load
 * error that refuses it.
 */
@Schema(
    oneOf = {StructureLoaded.class, StructureRefused.class},
    discriminatorProperty = "kind",
    discriminatorMapping = {
      @DiscriminatorMapping(value = StructureLoaded.KIND, schema = StructureLoaded.class),
      @DiscriminatorMapping(value = StructureRefused.KIND, schema = StructureRefused.class)
    })
public sealed interface StructureResponse permits StructureLoaded, StructureRefused {}
