import { Modal, ModalBody, ModalFooter, ModalHeader } from '@patternfly/react-core';
import type { ComponentType, ReactNode } from 'react';

/** An icon button of a toolbar, named by its label (the prototype's ToolbarTools Tool). */
export function Tool({ label, icon: Icon, onClick, disabled }: { label: string; icon: ComponentType; onClick: () => void; disabled?: boolean }) {
  return (
    <button type="button" className="def-tool" aria-label={label} title={label} disabled={disabled} onClick={onClick}>
      <Icon />
    </button>
  );
}

/** A modal dialog named by its title, with an optional footer (the prototype's Panels Dialog). */
export function Dialog({ title, close, children, footer, wide = false }: { title: string; close: () => void; children: ReactNode; footer: ReactNode; wide?: boolean }) {
  return (
    <Modal isOpen onClose={close} variant={wide ? 'medium' : 'small'} aria-label={title}>
      <ModalHeader title={title} />
      <ModalBody>{children}</ModalBody>
      <ModalFooter>{footer}</ModalFooter>
    </Modal>
  );
}
