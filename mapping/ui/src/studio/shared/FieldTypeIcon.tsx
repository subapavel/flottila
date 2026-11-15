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

/** A field's type as an icon (the prototype's FieldTypeIcon): a cube for a structure field, else a symbol of its value type. */
export function FieldTypeIcon({ type }: { type: string }) {
  const symbol = NUMERIC_TYPES.has(type) ? '#' : (SYMBOLS[type] ?? '·');
  return (
    <span className="field-type-icon" data-field-type={type} title={type} aria-hidden="true">
      {type === 'structure' ? <CubeIcon /> : symbol}
    </span>
  );
}
