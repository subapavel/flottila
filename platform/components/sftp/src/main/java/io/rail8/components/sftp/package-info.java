/**
 * The {@code sftp} component (D-076, ARCHITECTURE §7): {@link io.rail8.components.sftp.SftpComponent} — a polling
 * consumer on the Apache MINA SSHD client that deletes or moves a file only after the handler's answer (the durable save
 * is the flow's); no producer before Phase 4. Depends on rail8-api and MINA SSHD only.
 *
 * @since 0.1
 */
package io.rail8.components.sftp;
