import {
  Alert,
  Button,
  Form,
  FormAlert,
  FormGroup,
  FormHelperText,
  HelperText,
  HelperTextItem,
  Modal,
  ModalBody,
  ModalFooter,
  ModalHeader,
  TextArea,
  TextInput,
} from '@patternfly/react-core';
import { useMutation } from '@tanstack/react-query';
import { useId, useState, type FormEvent } from 'react';
import { describeError, type ApiClient, type CreateMappingRequest } from '../../api/client';

// A workspace path segment as the service accepts it (ADR-0024): the service checks again and refuses reserved names.
const SEGMENT = /^[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}$/;
/** An id has at most 16 segments (ADR-0024): the name and at most 15 folders. */
const MAX_FOLDERS = 15;
/** ADR-0029: a description of at most 4,000 code points. */
const MAX_DESCRIPTION = 4000;

const count = (n: number) => n.toLocaleString('en-US');

function nameError(name: string): string | undefined {
  return SEGMENT.test(name) ? undefined : 'Use letters, digits, ".", "_" and "-", up to 64, not starting with ".".';
}

function packageError(pkg: string): string | undefined {
  if (pkg === '') return undefined;
  const folders = pkg.split('/');
  if (!folders.every((folder) => SEGMENT.test(folder))) return 'Use folder names of letters, digits, ".", "_" and "-", separated by "/".';
  return folders.length > MAX_FOLDERS ? `Use at most ${MAX_FOLDERS} folders.` : undefined;
}

function descriptionError(description: string): string | undefined {
  const length = [...description].length;
  return length > MAX_DESCRIPTION ? `Use at most ${count(MAX_DESCRIPTION)} characters; this has ${count(length)}.` : undefined;
}

export interface NewMappingModalProps {
  api: ApiClient;
  onClose: () => void;
  /** Told the new mapping's id once the service created it. */
  onCreated: (id: string) => void;
}

/**
 * The New mapping modal of the start page (ADR-0028): Name, Package and Description, and no message choice; the
 * messages are chosen on the Signature tab (ADR-0029). The id is the package's folders and the name. Each field says
 * what it allows; the service checks again, and its refusal (400, 409 `mapping-exists`) shows above the fields.
 */
export function NewMappingModal({ api, onClose, onCreated }: NewMappingModalProps) {
  const [name, setName] = useState('');
  const [pkg, setPkg] = useState('');
  const [description, setDescription] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const create = useMutation({
    mutationFn: (request: CreateMappingRequest) => api.createMapping(request),
    onSuccess: (_, request) => onCreated(request.id),
  });
  const failure = create.isError ? describeError(create.error) : undefined;
  const ids = { title: useId(), description: useId(), form: useId(), name: useId(), pkg: useId(), text: useId() };

  const trimmedName = name.trim();
  const trimmedPkg = pkg.trim();
  // an empty name is asked for only once Create was chosen; any other error shows while typing
  const errors = {
    name: trimmedName === '' ? (submitted ? 'Enter a name.' : undefined) : nameError(trimmedName),
    pkg: packageError(trimmedPkg),
    description: descriptionError(description),
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    setSubmitted(true);
    if (trimmedName === '' || errors.name || errors.pkg || errors.description) return;
    create.mutate({ id: trimmedPkg === '' ? trimmedName : `${trimmedPkg}/${trimmedName}`, ...(description === '' ? {} : { description }) });
  };

  const helper = (id: string, error: string | undefined, hint: string) => (
    <FormHelperText>
      <HelperText id={id}>
        <HelperTextItem variant={error ? 'error' : 'default'}>{error ?? hint}</HelperTextItem>
      </HelperText>
    </FormHelperText>
  );

  return (
    <Modal variant="small" isOpen onClose={onClose} aria-labelledby={ids.title} aria-describedby={ids.description}>
      <ModalHeader
        title="New mapping"
        labelId={ids.title}
        description="Choose its source and target messages on the Signature tab once it is created."
        descriptorId={ids.description}
      />
      <ModalBody>
        <Form id={ids.form} onSubmit={submit} noValidate>
          {failure && (
            <FormAlert>
              <Alert variant="danger" isInline title={failure.title}>
                {failure.detail}
              </Alert>
            </FormAlert>
          )}
          <FormGroup label="Name" isRequired fieldId={ids.name}>
            <TextInput
              id={ids.name}
              isRequired
              value={name}
              onChange={(_, value) => setName(value)}
              validated={errors.name ? 'error' : 'default'}
              aria-describedby={`${ids.name}-helper`}
            />
            {helper(`${ids.name}-helper`, errors.name, 'The file name of the mapping in its package.')}
          </FormGroup>
          <FormGroup label="Package" fieldId={ids.pkg}>
            <TextInput
              id={ids.pkg}
              value={pkg}
              onChange={(_, value) => setPkg(value)}
              validated={errors.pkg ? 'error' : 'default'}
              aria-describedby={`${ids.pkg}-helper`}
            />
            {helper(`${ids.pkg}-helper`, errors.pkg, 'The workspace folder, such as orders/inbound; empty for the top folder.')}
          </FormGroup>
          <FormGroup label="Description" fieldId={ids.text}>
            <TextArea
              id={ids.text}
              value={description}
              onChange={(_, value) => setDescription(value)}
              validated={errors.description ? 'error' : 'default'}
              aria-describedby={`${ids.text}-helper`}
              resizeOrientation="vertical"
            />
            {helper(`${ids.text}-helper`, errors.description, `Up to ${count(MAX_DESCRIPTION)} characters.`)}
          </FormGroup>
        </Form>
      </ModalBody>
      <ModalFooter>
        <Button type="submit" form={ids.form} isLoading={create.isPending} isDisabled={create.isPending}>
          Create
        </Button>
        <Button variant="link" onClick={onClose}>
          Cancel
        </Button>
      </ModalFooter>
    </Modal>
  );
}
