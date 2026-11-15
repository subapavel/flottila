import { Button, Modal, ModalBody, ModalHeader } from '@patternfly/react-core';
import { useState, type FormEvent } from 'react';
import type { FieldPath } from '../../../generated/mapping-model';
import { localPath, stepName } from '../../../model/field-path';

export interface RemapDialogProps {
  /** The field the orphaned box reads, which the source structure lacks. */
  field: FieldPath;
  /** The source fields it can read instead, in tree order. */
  choices: readonly FieldPath[];
  remap: (field: FieldPath) => void;
  close: () => void;
}

/**
 * The remap of an orphaned source-field box (GLOSSARY: Remap): a field of the source structure, chosen from all of them
 * in tree order; the box keeps its authoring id and its wires.
 */
export function RemapDialog({ field, choices, remap, close }: RemapDialogProps) {
  const [choice, setChoice] = useState('');
  const title = `Remap · ${stepName(field)}`;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    remap(choice);
  };
  return (
    <Modal isOpen variant="small" onClose={close} aria-label={title}>
      <ModalHeader title={title} />
      <ModalBody>
        <form className="fn-parameters" onSubmit={submit}>
          <p>The source structure has no field /{localPath(field)}. Choose the source field the box reads instead; it keeps its connections.</p>
          <label>
            Source field
            <select aria-label="Source field" value={choice} onChange={(e) => setChoice(e.target.value)}>
              <option value="" disabled>
                Choose a source field
              </option>
              {choices.map((path) => (
                <option key={path} value={path}>
                  /{localPath(path)}
                </option>
              ))}
            </select>
          </label>
          <div className="form-actions">
            <Button type="submit" isDisabled={choice === ''}>
              Remap
            </Button>
            <Button type="button" variant="link" onClick={close}>
              Cancel
            </Button>
          </div>
        </form>
      </ModalBody>
    </Modal>
  );
}
