import { Button, Modal, ModalBody, ModalFooter, ModalHeader } from '@patternfly/react-core';
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
 * The prototype's SimpleDateFormat helper behind "Choose format…": a date and a time pattern with their separators,
 * the result previewed, applied to the parameter it was opened for.
 */
export function DateFormatHelp({ apply, close }: { apply: (format: string) => void; close: () => void }) {
  const [parts, setParts] = useState<Parts>({ date: datePatterns[0]!, time: '', ds: '/', ts: '/', join: ' ' });
  const result = [joinPattern(parts.date, parts.ds), joinPattern(parts.time, parts.ts)].filter(Boolean).join(parts.join);
  const select = (key: keyof Parts, label: string, choices: string[]) => (
    <label>
      {label}
      <select aria-label={label} value={parts[key]} onChange={(e) => setParts({ ...parts, [key]: e.target.value })}>
        {choices.map((v) => (
          <option key={v} value={v}>
            {v === ' ' ? 'Space' : v || 'None'}
          </option>
        ))}
      </select>
    </label>
  );
  return (
    <Modal isOpen onClose={close} variant="small" aria-label="SimpleDateFormat">
      <ModalHeader title="SimpleDateFormat" />
      <ModalBody>
        <div className="fn-parameters">
          <div className="fn-pair-columns">
            {select('date', 'Date format', datePatterns)}
            {select('time', 'Time format', ['', 'HHmmss', 'HHmm'])}
          </div>
          {select('ds', 'Date Separator', separators)}
          {select('ts', 'Time Separator', separators)}
          {select('join', 'Date/Time Separator', [' ', ','])}
          <div>
            <label htmlFor="date-format-preview">Format preview</label>
            <output id="date-format-preview">{result || '—'}</output>
          </div>
        </div>
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
