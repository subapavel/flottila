package io.flottila.mapping.service;

import org.eclipse.microprofile.openapi.annotations.media.DiscriminatorMapping;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The result of a test run (ADR-0015, ADR-0024): output, the diagnostics that refuse the working
 * copy, or the runtime error that stopped the run. Output and error carry the run's trace; a
 * refused working copy never ran, so it has none (#53).
 */
@Schema(
    oneOf = {TestOutput.class, TestRefused.class, TestError.class},
    discriminatorProperty = "kind",
    discriminatorMapping = {
      @DiscriminatorMapping(value = TestOutput.KIND, schema = TestOutput.class),
      @DiscriminatorMapping(value = TestRefused.KIND, schema = TestRefused.class),
      @DiscriminatorMapping(value = TestError.KIND, schema = TestError.class)
    })
public sealed interface TestResult permits TestOutput, TestRefused, TestError {}
