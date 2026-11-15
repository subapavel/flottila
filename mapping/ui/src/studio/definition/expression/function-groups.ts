import type { FunctionCatalogue } from '../../../api/client';

/** The drilldown's groups, in the prototype's order (its SAP PO StandardFunctions library). */
export const GROUPS = ['Arithmetic', 'Boolean', 'Text', 'Conversions', 'Date', 'Constants', 'Node Functions', 'Statistic'] as const;
export type Group = (typeof GROUPS)[number];

/** The follow-up issues of the functions the catalogue lacks (spec #152's preview table). */
const LOOKUPS = 165;
const VALUE_MAPPING = 166;
const MISSING_FUNCTIONS = 172;

/**
 * The prototype's drilldown in its order: each entry's group and our catalogue name, or for an entry the catalogue lacks,
 * the prototype's name, its input count and its follow-up issue. The 9 renamed functions (`1/x`, `FixValues`,
 * `FormatNum`, `DateTrans`, `DateBefore`, `DateAfter`, `CompareDates`, `CopyValue`, `SplitByValue`) carry our name.
 */
const PROTOTYPE: readonly (readonly [Group, string] | readonly [Group, string, number, number])[] = [
  ...(['add', 'subtract', 'equalsA', 'abs', 'sqrt', 'sqr', 'sign', 'neg', 'inv', 'power', 'less', 'greater', 'multiply', 'divide', 'max', 'min', 'ceil', 'floor', 'round', 'counter'].map(
    (fn) => ['Arithmetic', fn] as const,
  )),
  ...(['and', 'or', 'not', 'equals', 'notEquals', 'if'].map((fn) => ['Boolean', fn] as const)),
  ['Boolean', 'ifS', 3, MISSING_FUNCTIONS],
  ['Text', 'substring'],
  ['Conversions', 'fixValues'],
  ['Arithmetic', 'formatNum'],
  ['Text', 'concat'],
  ['Text', 'equalsS'],
  ['Text', 'indexOf'],
  ['Text', 'indexOf', 3, MISSING_FUNCTIONS],
  ...(['currentDate', 'dateTrans', 'dateBefore', 'dateAfter', 'compareDates'].map((fn) => ['Date', fn] as const)),
  ['Text', 'lastIndexOf'],
  ['Text', 'lastIndexOf', 3, MISSING_FUNCTIONS],
  ...(['compare', 'replaceString', 'length', 'endsWith', 'startsWith'].map((fn) => ['Text', fn] as const)),
  ['Text', 'startsWith', 3, MISSING_FUNCTIONS],
  ...(['toUpperCase', 'trim', 'toLowerCase'].map((fn) => ['Text', fn] as const)),
  ['Constants', 'constant'],
  ['Constants', 'copyValue'],
  ...(['createIf', 'removeContexts', 'replaceValue', 'exists'].map((fn) => ['Node Functions', fn] as const)),
  ['Boolean', 'ifWithoutElse'],
  ['Boolean', 'ifSWithoutElse', 2, MISSING_FUNCTIONS],
  ['Node Functions', 'splitByValue'],
  ['Node Functions', 'collapseContexts'],
  ['Conversions', 'Value mapping', 1, VALUE_MAPPING],
  ...(['sum', 'average', 'count', 'index'].map((fn) => ['Statistic', fn] as const)),
  ['Node Functions', 'useOneAsMany'],
  ['Constants', 'sender', 0, MISSING_FUNCTIONS],
  ['Constants', 'receiver', 0, MISSING_FUNCTIONS],
  ...(['sort', 'sortByKey', 'mapWithDefault', 'formatByExample'].map((fn) => ['Node Functions', fn] as const)),
  ['Conversions', 'JDBC Lookup', 1, LOOKUPS],
  ['Conversions', 'RFC Lookup', 0, LOOKUPS],
  ['Constants', 'xsi:nil', 0, MISSING_FUNCTIONS],
  ['Boolean', 'isNil'],
];

/** The group of a catalogue function the prototype does not list, by its function family. */
const FAMILY_GROUPS: Record<string, Group> = {
  arithmetic: 'Arithmetic',
  boolean: 'Boolean',
  text: 'Text',
  conversions: 'Conversions',
  date: 'Date',
  constant: 'Constants',
  context: 'Node Functions',
  statistic: 'Statistic',
};

/** The names the prototype lists with an overload, which show their input count. */
const OVERLOADED = new Set(['indexOf', 'lastIndexOf', 'startsWith']);

/** One function of the drilldown. */
export interface DrilldownFunction {
  /** Unique within the drilldown. */
  key: string;
  name: string;
  /** The catalogue function to add; absent for a function the catalogue lacks, which is shown disabled. */
  fn?: string;
  /** The input count an overloaded name shows. */
  overload?: number;
  /** Whether the function has box parameters. */
  configurable: boolean;
  /** The follow-up issue of a function the catalogue lacks. */
  issue?: number;
  /** The key of a preview function (`mock/preview-functions`) that stands in for a function the catalogue lacks. */
  preview?: string;
}

/** The prototype's names of the functions offered as previews, and their keys. */
const PREVIEWS: Record<string, string> = { 'Value mapping': 'valuemap', 'JDBC Lookup': 'tableLookup', 'RFC Lookup': 'rfclookup' };

export interface DrilldownGroup {
  group: Group;
  functions: DrilldownFunction[];
}

/**
 * The functions drilldown (spec #152): every catalogue function under its prototype group in the prototype's order, a
 * catalogue function the prototype does not list at the end of its family's group (a family without a group goes to
 * Node Functions), and the prototype's functions the catalogue lacks with their follow-up issue: Value mapping and the
 * lookups as previews, the others disabled.
 */
export function drilldown(catalogue: FunctionCatalogue): DrilldownGroup[] {
  const groups = new Map<Group, DrilldownFunction[]>(GROUPS.map((g) => [g, []]));
  const listed = new Set<string>();
  const configurable = (fn: string) => (catalogue[fn]?.params?.length ?? 0) > 0;
  for (const [i, [group, name, inputs, issue]] of PROTOTYPE.entries()) {
    const known = issue === undefined && Object.hasOwn(catalogue, name);
    if (issue === undefined && !known) continue;
    if (known) listed.add(name);
    const count = known ? (catalogue[name]!.inputs?.length ?? 0) : inputs!;
    groups.get(group)!.push({
      key: `${i}:${name}`,
      name,
      ...(known && { fn: name }),
      ...(OVERLOADED.has(name) && { overload: count }),
      configurable: known && configurable(name),
      ...(issue !== undefined && { issue }),
      ...(Object.hasOwn(PREVIEWS, name) && { preview: PREVIEWS[name] }),
    });
  }
  for (const name of Object.keys(catalogue).sort()) {
    if (listed.has(name)) continue;
    groups.get(FAMILY_GROUPS[catalogue[name]!.family] ?? 'Node Functions')!.push({ key: name, name, fn: name, configurable: configurable(name) });
  }
  return GROUPS.map((group) => ({ group, functions: groups.get(group)! }));
}
