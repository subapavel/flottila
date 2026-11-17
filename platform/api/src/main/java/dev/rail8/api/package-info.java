/**
 * The public types every extension sees: the message ({@link dev.rail8.api.Envelope}, {@link dev.rail8.api.Body}), the
 * pipeline ({@link dev.rail8.api.Step}, {@link dev.rail8.api.Stage}, {@link dev.rail8.api.Pipeline}), the Component SPI,
 * the {@link dev.rail8.api.BlobStore} and the {@link dev.rail8.api.Runtime}. The package depends on the JDK and on Vert.x
 * {@code Future}, {@code Buffer}, {@code ReadStream} and {@code WriteStream} only; an architecture test enforces this.
 */
package dev.rail8.api;
