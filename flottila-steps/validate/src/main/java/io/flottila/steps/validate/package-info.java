/**
 * The {@code xsd} step (ARCHITECTURE §7, §13): XML Schema validation of the body's bytes on the JDK's
 * {@code javax.xml.validation}, schemas compiled at build time by content hash, no network. Depends on
 * flottila-api only; register {@link io.flottila.steps.validate.XsdValidate#steps()} with the flow builder.
 */
package io.flottila.steps.validate;
