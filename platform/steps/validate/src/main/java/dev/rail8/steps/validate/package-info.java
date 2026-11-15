/**
 * The {@code xsd} step (ARCHITECTURE §7, §13): XML Schema validation of the body's bytes on the JDK's
 * {@code javax.xml.validation}, schemas compiled at build time by content hash, no network. Depends on
 * rail8-api only; register {@link dev.rail8.steps.validate.XsdValidate#steps()} with the flow builder.
 */
package dev.rail8.steps.validate;
