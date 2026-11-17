import { Button, Content, Form, FormGroup, FormHelperText, HelperText, HelperTextItem, Modal, ModalBody, ModalFooter, ModalHeader, TextInput } from '@patternfly/react-core';
import { useId, useState } from 'react';
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

const NAME = /^[A-Za-z_][A-Za-z0-9_]*$/;

/**
 * The Add Variable dialog: a name of a letter or `_` and then letters, digits or `_`, at most 90 characters, one the
 * parent does not use yet.
 */
export function AddVariable({ parent, taken, create, close }: AddVariableProps) {
  const [name, setName] = useState('');
  const formId = useId();
  const value = name.trim();
  const valid = NAME.test(value) && value.length <= 90;
  const used = valid && taken(`${parent}/$${value}`);
  return (
    <Modal isOpen variant="small" aria-label="Add Variable" onClose={close}>
      <ModalHeader title="Add Variable" description={`/${localPath(parent)}`} />
      <ModalBody>
        <Form
          id={formId}
          onSubmit={(e) => {
            e.preventDefault();
            if (valid && !used) create(value);
          }}
        >
          <FormGroup label="Variable name" isRequired fieldId={`${formId}-name`}>
            <TextInput id={`${formId}-name`} isRequired maxLength={90} value={name} validated={value && (!valid || used) ? 'error' : 'default'} onChange={(_event, text) => setName(text)} />
            <FormHelperText>
              <HelperText>
                <HelperTextItem variant={value && (!valid || used) ? 'error' : 'default'}>
                  {used ? 'This field already has a variable or a child of that name.' : 'A letter or _, then letters, digits or _.'}
                </HelperTextItem>
              </HelperText>
            </FormHelperText>
          </FormGroup>
        </Form>
        <Content component="small">A preview: the variable is kept in this browser only, never in the mapping file.</Content>
      </ModalBody>
      <ModalFooter>
        <Button type="submit" form={formId} isDisabled={!valid || used}>
          Create
        </Button>
        <Button variant="link" onClick={close}>
          Cancel
        </Button>
      </ModalFooter>
    </Modal>
  );
}
