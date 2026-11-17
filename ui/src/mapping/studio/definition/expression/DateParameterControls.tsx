import { Button, Form, FormGroup, FormSelect, FormSelectOption, Grid, GridItem, Modal, ModalBody, ModalFooter, ModalHeader } from '@patternfly/react-core';
import { useState } from 'react';

const datePatterns = ['ddMMyyyy', 'MMddyyyy', 'yyyyMMdd', 'MMyyyydd', 'ddyyyyMM', 'yyyyddMM', 'yyddMM', 'ddMMyy', 'MMddyy', ''];
const separators = ['/', ':', '-', ' '];

interface Parts {
  date: string;
  time: string;
  ds: string;
  ts: string;
  join: string;
}

/** A date or time pattern's letter runs joined by `separator` (`ddMMyyyy` with `/` is `dd/MM/yyyy`). */
export const joinPattern = (pattern: string, separator: string) => (pattern.match(/y+|M+|d+|H+|m+|s+/g) ?? []).join(separator);

/**
 * The SimpleDateFormat helper behind "Choose format…": a date and a time pattern with their separators, the result
 * previewed, applied to the parameter it was opened for.
 */
export function DateFormatHelp({ apply, close }: { apply: (format: string) => void; close: () => void }) {
  const [parts, setParts] = useState<Parts>({ date: datePatterns[0]!, time: '', ds: '/', ts: '/', join: ' ' });
  const result = [joinPattern(parts.date, parts.ds), joinPattern(parts.time, parts.ts)].filter(Boolean).join(parts.join);
  const select = (key: keyof Parts, label: string, choices: string[]) => (
    <FormGroup label={label} fieldId={`date-format-${key}`}>
      <FormSelect id={`date-format-${key}`} aria-label={label} value={parts[key]} onChange={(_, value) => setParts({ ...parts, [key]: value })}>
        {choices.map((v) => (
          <FormSelectOption key={v} value={v} label={v === ' ' ? 'Space' : v || 'None'} />
        ))}
      </FormSelect>
    </FormGroup>
  );
  return (
    <Modal isOpen onClose={close} variant="small" aria-label="SimpleDateFormat">
      <ModalHeader title="SimpleDateFormat" />
      <ModalBody>
        <Form>
          <Grid hasGutter md={6}>
            <GridItem>{select('date', 'Date format', datePatterns)}</GridItem>
            <GridItem>{select('time', 'Time format', ['', 'HHmmss', 'HHmm'])}</GridItem>
          </Grid>
          {select('ds', 'Date Separator', separators)}
          {select('ts', 'Time Separator', separators)}
          {select('join', 'Date/Time Separator', [' ', ','])}
          <FormGroup label="Format preview" fieldId="date-format-preview">
            <output id="date-format-preview">{result || '—'}</output>
          </FormGroup>
        </Form>
      </ModalBody>
      <ModalFooter>
        <Button onClick={() => apply(result)}>Apply format</Button>
        <Button variant="link" onClick={close}>
          Cancel
        </Button>
      </ModalFooter>
    </Modal>
  );
}
