import { Icon } from '@patternfly/react-core';
import { CubeIcon } from '@patternfly/react-icons';

const NUMERIC_TYPES = new Set([
  'number',
  'decimal',
  'float',
  'double',
  'integer',
  'int',
  'long',
  'short',
  'byte',
  'positiveInteger',
  'negativeInteger',
  'nonPositiveInteger',
  'nonNegativeInteger',
  'unsignedLong',
  'unsignedInt',
  'unsignedShort',
  'unsignedByte',
]);
const SYMBOLS: Record<string, string> = { string: 'T', boolean: 'T/F', array: '[ ]', null: '∅', variable: 'V' };

/** A field's type as an icon: a cube for a structure field, else a symbol of its value type in the monospace font. */
export function FieldTypeIcon({ type }: { type: string }) {
  if (type === 'structure') {
    return (
      <Icon size="md" isInline title={type} aria-hidden="true">
        <CubeIcon />
      </Icon>
    );
  }
  return (
    <span title={type} aria-hidden="true" data-field-type={type} style={{ fontFamily: 'var(--pf-t--global--font--family--mono)', fontSize: 'var(--pf-t--global--font--size--body--sm)' }}>
      {NUMERIC_TYPES.has(type) ? '#' : (SYMBOLS[type] ?? '·')}
    </span>
  );
}
