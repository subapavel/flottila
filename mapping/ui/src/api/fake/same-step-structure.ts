import type { FieldDto, StructureLoaded } from '../client';

const ambiguous = (line: number, column: number) => ({
  code: 'ambiguous-field-path',
  side: 'both' as const,
  file: '../structures/refused/same-step.xsd',
  line,
  column,
});

const leaf = (step: string, min: number, refusal?: FieldDto['refusal']): FieldDto => ({
  kind: 'element',
  step,
  namespace: '',
  localName: step,
  min,
  max: 1,
  valueType: 'xs:string',
  nillable: false,
  ...(refusal && { refusal }),
});

/**
 * The reply of `POST /api/structures` for `corpus/structures/refused/same-step.xsd`, root `Doc`, from `mappings/`, as
 * the real service gave it on 2026-11-06 (#116): the same-step siblings `Note` and `Ref` each carry
 * `ambiguous-field-path` at the step's second declaration (ADR-0008, ADR-0027).
 */
export const sameStepStructure: StructureLoaded = {
  kind: 'loaded',
  fingerprint: 'sha256:d26971c5a6f2df7fa0bf9f7287ae22f1e93ef12dd6ea4ac8ce748990ba0ec32e',
  root: { kind: 'element', step: 'Doc', namespace: '', localName: 'Doc', min: 1, max: 1, nillable: false, type: '/Doc' },
  types: {
    '/Doc': [
      leaf('Id', 1),
      leaf('Note', 0, ambiguous(14, 65)),
      leaf('Code', 0),
      leaf('Note', 0, ambiguous(14, 65)),
      { kind: 'element', step: 'Head', namespace: '', localName: 'Head', min: 0, max: 1, nillable: false, type: '/Doc/Head' },
    ],
    '/Doc/Head': [leaf('Ref', 1, ambiguous(19, 56)), leaf('Ref', 1, ambiguous(19, 56))],
  },
};
