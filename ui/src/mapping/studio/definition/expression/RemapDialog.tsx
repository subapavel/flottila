import { Button, Content, Form, FormGroup, FormSelect, FormSelectOption, Modal, ModalBody, ModalFooter, ModalHeader } from '@patternfly/react-core';
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
 * The remap of an orphaned source-field box: a field of the source structure, chosen from all of them in tree order;
 * the box keeps its authoring id and its wires.
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
        <Form id="remap-form" onSubmit={submit}>
          <Content component="p">The source structure has no field /{localPath(field)}. Choose the source field the box reads instead; it keeps its connections.</Content>
          <FormGroup label="Source field" fieldId="remap-source-field">
            <FormSelect id="remap-source-field" aria-label="Source field" value={choice} onChange={(_, value) => setChoice(value)}>
              <FormSelectOption value="" label="Choose a source field" isDisabled />
              {choices.map((path) => (
                <FormSelectOption key={path} value={path} label={`/${localPath(path)}`} />
              ))}
            </FormSelect>
          </FormGroup>
        </Form>
      </ModalBody>
      <ModalFooter>
        <Button type="submit" form="remap-form" isDisabled={choice === ''}>
          Remap
        </Button>
        <Button variant="link" onClick={close}>
          Cancel
        </Button>
      </ModalFooter>
    </Modal>
  );
}
