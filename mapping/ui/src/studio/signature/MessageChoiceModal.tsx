import {
  Alert,
  Button,
  Form,
  FormGroup,
  FormSelect,
  FormSelectOption,
  Modal,
  ModalBody,
  ModalFooter,
  ModalHeader,
  TextArea,
  TextInput,
} from '@patternfly/react-core';
import { useId, useState, type FormEvent } from 'react';
import type { Diagnostic } from '../../api/client';
import { choiceOf, draftOf, ROOT_KINDS, type ChoiceDraft, type Direction } from './message-choice';
import type { MessageChoice, RootKind } from '../shared/structure-names';

/** Why a choice was not applied: a title, and the diagnostics of a structure the service refused. */
export interface ChoiceFailure {
  title: string;
  diagnostics: Diagnostic[];
}

export interface MessageChoiceModalProps {
  title: string;
  description: string;
  /** The message the row has, whose bundle and root the fields start from. */
  initial?: MessageChoice;
  /** The root kind the row's Type select asked for, replacing the initial one. */
  kind?: RootKind;
  /** Applies the choice; resolves to why it was not applied, or `null` once it was, and the caller closes the dialog. */
  onApply: (choice: MessageChoice) => Promise<ChoiceFailure | null>;
  onClose: () => void;
}

/**
 * The message choice of the Signature tab, in the prototype's catalog dialog (SignatureCatalog): the bundle, one
 * reference per line relative to the mapping's package, and the root selector, as the old New mapping form chose a
 * side. Apply is enabled once the bundle and the root's names are given; a failure shows above the fields.
 */
export function MessageChoiceModal({ title, description, initial, kind, onApply, onClose }: MessageChoiceModalProps) {
  const [draft, setDraft] = useState(() => draftOf(initial, kind));
  const [applying, setApplying] = useState(false);
  const [failure, setFailure] = useState<ChoiceFailure | null>(null);
  const ids = { title: useId(), description: useId(), form: useId() };
  const choice = choiceOf(draft);
  const set = (change: Partial<ChoiceDraft>) => setDraft((current) => ({ ...current, ...change }));
  const fieldId = (name: string) => `${ids.form}-${name}`;

  // A form submits only by its default button, Apply, also on Enter (HTML implicit submission); Apply is disabled
  // without a choice and while one is applied.
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setApplying(true);
    setFailure(null);
    const failed = await onApply(choice!);
    setApplying(false);
    setFailure(failed);
  };

  const text = (name: string, label: string, value: string, change: (value: string) => void) => (
    <FormGroup label={label} fieldId={fieldId(name)}>
      <TextInput id={fieldId(name)} value={value} onChange={(_, next) => change(next)} />
    </FormGroup>
  );

  return (
    <Modal variant="medium" isOpen onClose={onClose} aria-labelledby={ids.title} aria-describedby={ids.description}>
      <ModalHeader title={title} labelId={ids.title} description={description} descriptorId={ids.description} />
      <ModalBody>
        <Form id={ids.form} onSubmit={(event) => void submit(event)}>
          {failure && (
            <Alert variant="danger" isInline title={failure.title}>
              <ul aria-label="Message diagnostics">
                {failure.diagnostics.map(({ severity, code, message }, i) => (
                  <li key={i}>
                    {severity} <code>{code}</code>: {message}
                  </li>
                ))}
              </ul>
            </Alert>
          )}
          <FormGroup label="Bundle" fieldId={fieldId('bundle')}>
            <TextArea
              id={fieldId('bundle')}
              value={draft.bundle}
              onChange={(_, bundle) => set({ bundle })}
              placeholder="One path per line, relative to the mapping's package"
              resizeOrientation="vertical"
            />
          </FormGroup>
          <FormGroup label="Root" fieldId={fieldId('kind')}>
            <FormSelect id={fieldId('kind')} value={draft.kind} onChange={(_, value) => set({ kind: value as RootKind })}>
              {ROOT_KINDS.map(([value, label]) => (
                <FormSelectOption key={value} value={value} label={label} />
              ))}
            </FormSelect>
          </FormGroup>
          {draft.kind === 'element' && text('element', 'Root element', draft.element, (element) => set({ element }))}
          {draft.kind === 'wsdl' && (
            <>
              {text('interface', 'Interface', draft.wsdlInterface, (wsdlInterface) => set({ wsdlInterface }))}
              {text('operation', 'Operation', draft.operation, (operation) => set({ operation }))}
              <FormGroup label="Direction" fieldId={fieldId('direction')}>
                <FormSelect id={fieldId('direction')} value={draft.direction} onChange={(_, direction) => set({ direction: direction as Direction })}>
                  <FormSelectOption value="input" label="input" />
                  <FormSelectOption value="output" label="output" />
                  <FormSelectOption value="fault" label="fault" />
                </FormSelect>
              </FormGroup>
              {draft.direction === 'fault' && text('fault', 'Fault', draft.fault, (fault) => set({ fault }))}
            </>
          )}
        </Form>
      </ModalBody>
      <ModalFooter>
        <Button type="submit" form={ids.form} isDisabled={!choice || applying} isLoading={applying}>
          Apply
        </Button>
        <Button variant="link" onClick={onClose}>
          Cancel
        </Button>
      </ModalFooter>
    </Modal>
  );
}
