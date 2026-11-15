import { Alert, Button, Modal, ModalBody, ModalFooter, ModalHeader } from '@patternfly/react-core';
import { useMutation } from '@tanstack/react-query';
import { useStore } from 'zustand';
import { describeError } from '../../api/client';
import type { WorkingCopyStore } from '../store/working-copy';

/**
 * The save conflict (ADR-0027): the file changed on disk since it was opened. Reload drops the working copy and its
 * undo history and loads the file; Keep editing keeps the working copy, and the next Save meets the conflict again.
 */
export function SaveConflict({ store }: { store: WorkingCopyStore }) {
  const open = useStore(store, (s) => s.conflict);
  const reload = useMutation({ mutationFn: () => store.getState().reload() });
  const failure = reload.isError ? describeError(reload.error) : undefined;
  const keepEditing = () => {
    reload.reset();
    store.getState().keepEditing();
  };

  return (
    <Modal variant="small" isOpen={open} onClose={keepEditing} aria-labelledby="save-conflict-title">
      <ModalHeader title="Save conflict" labelId="save-conflict-title" />
      <ModalBody>
        <p>The mapping changed on disk since it was opened, so it was not saved.</p>
        <p>Reload drops your changes and the undo history and opens the file from disk.</p>
        {failure && (
          <Alert variant="danger" isInline title={failure.title}>
            {failure.detail}
          </Alert>
        )}
      </ModalBody>
      <ModalFooter>
        <Button onClick={() => reload.mutate()} isLoading={reload.isPending} isDisabled={reload.isPending}>
          Reload
        </Button>
        <Button variant="secondary" onClick={keepEditing}>
          Keep editing
        </Button>
      </ModalFooter>
    </Modal>
  );
}
