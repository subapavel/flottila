import { FormGroup, FormHelperText, FormSelect, FormSelectOption, HelperText, HelperTextItem, TextInput } from '@patternfly/react-core';
import { useState } from 'react';
import { KNOWN_TYPES, type SettingsField } from './settings';

export interface FieldControlProps {
  /** The field; a key of the file that no field describes comes as a field of no known type. */
  field: SettingsField;
  /** The option's value in the file or the draft; undefined when it is not set. */
  value: unknown;
  readOnly: boolean;
  /** Told the new value, or undefined when the option is cleared. */
  onChange: (value: unknown) => void;
  /** Told whether a raw editor holds text that is no JSON, which cannot be saved. */
  onInvalid: (invalid: boolean) => void;
  /** What the check finds wrong with the option, or undefined. */
  problem?: string;
}

const shown = (value: unknown) => (value === undefined || value === null ? '' : typeof value === 'string' ? value : JSON.stringify(value));

/** The text that stands in an empty control: the default when there is one. */
function placeholder(field: SettingsField): string | undefined {
  return field.default === undefined ? undefined : `Default: ${shown(field.default)}`;
}

/** A whole number as the draft keeps it: a number when the text is one, else the text, which the check refuses. */
function integer(text: string): unknown {
  if (text.trim() === '') return undefined;
  return /^-?\d+$/.test(text.trim()) ? Number(text.trim()) : text;
}

/**
 * One option of an endpoint as a form group: its name as the label, its help text below, and the problem the check
 * finds. Read-only it shows the value as text, a secret masked. Editing, text and secrets are text inputs (empty is not
 * set), a whole number a text input that keeps what was typed, true or false and a choice a select with "Not set",
 * and a field of a type the form does not know a raw editor of its value as JSON.
 */
export function FieldControl({ field, value, readOnly, onChange, onInvalid, problem }: FieldControlProps) {
  const id = `setting-${field.name}`;
  const known = KNOWN_TYPES.has(field.type);
  const [raw, setRaw] = useState<string | null>(null);
  const rawInvalid = raw !== null;
  const validated = problem !== undefined || rawInvalid ? 'error' : 'default';

  const text = known ? shown(value) : value === undefined ? '' : JSON.stringify(value);

  let control;
  if (readOnly) {
    control = (
      <TextInput
        id={id}
        readOnlyVariant="default"
        type={field.type === 'secret' ? 'password' : 'text'}
        value={text}
        placeholder={placeholder(field) ?? 'Not set'}
        validated={validated}
      />
    );
  } else if (field.type === 'boolean' || field.type === 'choice') {
    const values = field.type === 'boolean' ? ['true', 'false'] : (field.choices ?? []);
    const unset = field.default === undefined ? 'Not set' : `Not set (default: ${shown(field.default)})`;
    control = (
      <FormSelect
        id={id}
        value={shown(value)}
        isRequired={field.required}
        validated={validated}
        onChange={(_, chosen) => onChange(chosen === '' ? undefined : field.type === 'boolean' ? chosen === 'true' : chosen)}
      >
        <FormSelectOption value="" label={unset} />
        {/* a value the file holds that is none of the choices stays visible, so the check can name it */}
        {value !== undefined && value !== null && !values.includes(shown(value)) && <FormSelectOption value={shown(value)} label={shown(value)} />}
        {values.map((v) => (
          <FormSelectOption key={v} value={v} label={v} />
        ))}
      </FormSelect>
    );
  } else if (known) {
    control = (
      <TextInput
        id={id}
        type={field.type === 'secret' ? 'password' : 'text'}
        inputMode={field.type === 'integer' ? 'numeric' : undefined}
        value={shown(value)}
        placeholder={placeholder(field)}
        isRequired={field.required}
        validated={validated}
        onChange={(_, text) => onChange(field.type === 'integer' ? integer(text) : text === '' ? undefined : text)}
      />
    );
  } else {
    control = (
      <TextInput
        id={id}
        value={raw ?? text}
        validated={validated}
        onChange={(_, text) => {
          if (text.trim() === '') {
            setRaw(null);
            onInvalid(false);
            onChange(undefined);
            return;
          }
          try {
            onChange(JSON.parse(text));
            setRaw(null);
            onInvalid(false);
          } catch {
            setRaw(text);
            onInvalid(true);
          }
        }}
      />
    );
  }

  return (
    <FormGroup label={field.name} isRequired={field.required} fieldId={id}>
      {control}
      <FormHelperText>
        <HelperText>
          <HelperTextItem>{field.help}</HelperTextItem>
          {!known && <HelperTextItem>Written as JSON.</HelperTextItem>}
          {rawInvalid && <HelperTextItem variant="error">is not JSON</HelperTextItem>}
          {problem !== undefined && <HelperTextItem variant="error">{problem}</HelperTextItem>}
        </HelperText>
      </FormHelperText>
    </FormGroup>
  );
}
