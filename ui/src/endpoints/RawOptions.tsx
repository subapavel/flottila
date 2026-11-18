import { FormGroup, FormHelperText, HelperText, HelperTextItem, TextArea } from '@patternfly/react-core';
import { useState } from 'react';
import type { Options } from './settings';

export interface RawOptionsProps {
  options: Options;
  readOnly: boolean;
  /** Told the new options each time the text is a JSON object. */
  onChange: (options: Options) => void;
  /** Told whether the text is no JSON object, which cannot be saved. */
  onInvalid: (invalid: boolean) => void;
}

const isOptions = (value: unknown): value is Options => typeof value === 'object' && value !== null && !Array.isArray(value);

/** All of an endpoint's options as one JSON object, for a component that describes none of them. */
export function RawOptions({ options, readOnly, onChange, onInvalid }: RawOptionsProps) {
  const [text, setText] = useState(() => JSON.stringify(options, null, 2));
  const [invalid, setInvalid] = useState(false);
  return (
    <FormGroup label="Settings" fieldId="setting-raw">
      <TextArea
        id="setting-raw"
        readOnlyVariant={readOnly ? 'default' : undefined}
        value={text}
        rows={Math.min(20, text.split('\n').length + 1)}
        resizeOrientation="vertical"
        validated={invalid ? 'error' : 'default'}
        onChange={(_, next) => {
          setText(next);
          let parsed: unknown;
          try {
            parsed = JSON.parse(next);
          } catch {
            parsed = undefined;
          }
          setInvalid(!isOptions(parsed));
          onInvalid(!isOptions(parsed));
          if (isOptions(parsed)) onChange(parsed);
        }}
      />
      <FormHelperText>
        <HelperText>
          <HelperTextItem>The component describes no settings: its options are written here as one JSON object.</HelperTextItem>
          {invalid && <HelperTextItem variant="error">is not a JSON object</HelperTextItem>}
        </HelperText>
      </FormHelperText>
    </FormGroup>
  );
}
