import { LOOKUPS_ISSUE, VALUE_MAPPING_ISSUE, type PreviewParam } from './definition-preview';

/** A parameter of a preview function, as the prototype's standard-functions.json declares it. */
export interface PreviewFunctionParameter {
  key: string;
  label: string;
  type: 'String' | 'vmsagency' | 'vmtagency' | 'vmstrategy' | 'field_list' | 'bool' | 'Channel';
  initial: PreviewParam;
}

/** A function the catalogue lacks that the drilldown offers as a preview (spec #152): the prototype's entry. */
export interface PreviewFunction {
  key: 'valuemap' | 'tableLookup' | 'rfclookup';
  name: string;
  inputs: string[];
  /** Whether it has an output port: a lookup's ports are its export fields, which no table offers yet. */
  output: boolean;
  parameters: PreviewFunctionParameter[];
  issue: number;
}

/** The prototype's Value mapping, JDBC Lookup and RFC Lookup (standard-functions.json), in its order. */
export const PREVIEW_FUNCTIONS: readonly PreviewFunction[] = [
  {
    key: 'valuemap',
    name: 'Value mapping',
    inputs: ['a'],
    output: true,
    issue: VALUE_MAPPING_ISSUE,
    parameters: [
      // the prototype's http://sap.com/xi/XI goes: the studio has no namespace (ADR-0028, Terms)
      { key: 'context', label: 'Value-Mapping Context', type: 'String', initial: '' },
      { key: 'agency1', label: 'Source Agency', type: 'vmsagency', initial: 'SenderParty' },
      { key: 'schema1', label: 'Source Schema', type: 'String', initial: '' },
      { key: 'agency2', label: 'Target Agency', type: 'vmtagency', initial: 'ReceiverParty' },
      { key: 'schema2', label: 'Target Schema', type: 'String', initial: '' },
      { key: 'vmstrategy', label: 'Behaviour if lookup fails', type: 'vmstrategy', initial: '2' },
      { key: 'vmdefault', label: 'Default Value', type: 'String', initial: '' },
    ],
  },
  {
    key: 'tableLookup',
    name: 'JDBC Lookup',
    inputs: [],
    output: false,
    issue: LOOKUPS_ISSUE,
    parameters: [
      { key: 'ref', label: 'Database table', type: 'String', initial: '' },
      { key: 'ins', label: 'Input fields', type: 'field_list', initial: [] },
      { key: 'outs', label: 'Output fields', type: 'field_list', initial: [] },
      { key: 'split', label: 'Insert Context Change After Each Result Value', type: 'bool', initial: false },
      { key: 'channel', label: 'Communication Channel', type: 'Channel', initial: '' },
    ],
  },
  {
    key: 'rfclookup',
    name: 'RFC Lookup',
    inputs: [],
    output: false,
    issue: LOOKUPS_ISSUE,
    parameters: [
      { key: 'ref', label: 'RFC interface', type: 'String', initial: '' },
      { key: 'channel', label: 'Communication Channel', type: 'Channel', initial: '' },
      { key: 'useExc', label: 'Use Exception', type: 'bool', initial: false },
      { key: 'ins', label: 'Import fields', type: 'field_list', initial: [] },
      { key: 'outs', label: 'Export fields', type: 'field_list', initial: [] },
    ],
  },
];

export const previewFunction = (key: string): PreviewFunction | undefined => PREVIEW_FUNCTIONS.find((f) => f.key === key);

/** A preview function's parameters with their initial values (the prototype's standardNodeData). */
export const initialParams = (fn: PreviewFunction): Record<string, PreviewParam> => Object.fromEntries(fn.parameters.map((p) => [p.key, p.initial]));

/** The prototype's lookup catalogue: the one RFC module it offers; no database table. */
export const RFC_MODULE = 'SXIDEMO_AIRL_FLIGHT_CHECKAVAIL';
export const RFC_NAMESPACE = 'urn:sap-com:document:sap:rfc:functions';
