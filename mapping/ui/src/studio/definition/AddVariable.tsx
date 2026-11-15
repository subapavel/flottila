import { Button, Modal, ModalBody, ModalHeader } from '@patternfly/react-core';
import { useState } from 'react';
import type { FieldPath } from '../../generated/mapping-model';
import { localPath } from '../../model/field-path';

export interface AddVariableProps {
  /** The structure field the variable goes under. */
  parent: FieldPath;
  /** Whether a path is in use: a variable's or a field's. */
  taken: (path: FieldPath) => boolean;
  create: (name: string) => void;
  close: () => void;
}

/**
 * The Add Variable dialog (the prototype's): a name of a letter or `_` and then letters, digits or `_`, one the parent
 * does not use yet.
 */
export function AddVariable({ parent, taken, create, close }: AddVariableProps) {
  const [name, setName] = useState('');
  const value = name.trim();
  return (
    <Modal isOpen variant="small" aria-label="Add Variable" onClose={close}>
      <ModalHeader title="Add Variable" />
      <ModalBody>
        <form
          className="fn-parameters"
          onSubmit={(e) => {
            e.preventDefault();
            create(value);
          }}
        >
          <p className="muted">/{localPath(parent)}</p>
          <label className="form-field">
            Variable name
            <input required maxLength={90} pattern="[A-Za-z_][A-Za-z0-9_]*" value={name} onChange={(e) => setName(e.target.value)} />
          </label>
          <div className="form-actions">
            <Button type="submit" isDisabled={!value || taken(`${parent}/$${value}`)}>
              Create
            </Button>
            <Button variant="link" onClick={close}>
              Cancel
            </Button>
          </div>
        </form>
      </ModalBody>
    </Modal>
  );
}
