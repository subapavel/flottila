/**
 * The in-process mock component (ARCHITECTURE §7, §11): {@link io.flottila.components.mock.MockComponent} binds any
 * scheme, records what it was sent and replies per {@link io.flottila.components.mock.Behaviour} from the flow's
 * {@code *.test.yaml}; selected per environment, refused in a deployed one. Depends on flottila-api only.
 *
 * @since 0.1
 */
package io.flottila.components.mock;
