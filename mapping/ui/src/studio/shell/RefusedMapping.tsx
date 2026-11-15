import { Alert, Button } from '@patternfly/react-core';
import { describeError } from '../../api/client';

export interface RefusedMappingProps {
  /** Why the mapping did not open: a 422 `model-refused` with its diagnostics, or any other failure. */
  error: unknown;
  retrying: boolean;
  onRetry: () => void;
}

/** A mapping that does not open, read-only: the problem, its diagnostics and Retry, and no edit control (ADR-0027). */
export function RefusedMapping({ error, retrying, onRetry }: RefusedMappingProps) {
  const failure = describeError(error);
  return (
    <Alert
      variant="danger"
      isInline
      title={failure.title}
      actionLinks={
        <Button variant="link" isInline onClick={onRetry} isLoading={retrying} isDisabled={retrying}>
          Retry
        </Button>
      }
    >
      <p>{failure.detail}</p>
      <ul aria-label="Load diagnostics">
        {failure.diagnostics.map((d, i) => (
          <li key={i}>
            {d.severity} <code>{d.code}</code>: {d.message}
          </li>
        ))}
      </ul>
    </Alert>
  );
}
