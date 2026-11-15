import { Button, Modal, ModalBody, ModalFooter, ModalHeader } from '@patternfly/react-core';
import { useId } from 'react';

export interface DiscardDialogProps {
  isOpen: boolean;
  /** The mapping's name. */
  name: string;
  /** What Discard changes does next, ending the question: "return to view mode" or "leave the studio". */
  then: string;
  onDiscard: () => void;
  onKeepEditing: () => void;
}

/** "Discard changes?" (the prototype's discard dialog): Discard changes, or Keep editing, which has the focus. */
export function DiscardDialog({ isOpen, name, then, onDiscard, onKeepEditing }: DiscardDialogProps) {
  const titleId = useId();
  const keepEditingId = useId();
  return (
    <Modal variant="small" isOpen={isOpen} onClose={onKeepEditing} aria-labelledby={titleId} elementToFocus={`#${CSS.escape(keepEditingId)}`}>
      <ModalHeader title="Discard changes?" labelId={titleId} />
      <ModalBody>
        <p>
          Discard your unsaved changes to <strong>{name}</strong> and {then}?
        </p>
      </ModalBody>
      <ModalFooter>
        <Button variant="danger" onClick={onDiscard}>
          Discard changes
        </Button>
        <Button id={keepEditingId} variant="link" onClick={onKeepEditing}>
          Keep editing
        </Button>
      </ModalFooter>
    </Modal>
  );
}
