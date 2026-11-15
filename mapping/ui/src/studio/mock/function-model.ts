/**
 * The model of the Functions tab, a preview feature (ADR-0028, ADR-0030, #169): the user-defined functions, Java areas,
 * import instructions and archives of one mapping, kept in the browser only and never in the mapping file. Ported from
 * the prototype's functionModel.js (branch prototype/mapping-studio) in our terms: the method shape is ADR-0020's
 * (`MappingRun`, `Context`, `Queue`, `ContextOut`, `@Parameter`), the execution kinds are GLOSSARY's, the class name
 * comes from the mapping's name and the Java package from its mapping package.
 */

/** The Java areas every mapping has, before its functions: shared members, then the two lifecycle methods. */
export const AREAS = ['Attributes and Methods', 'init', 'cleanUp'] as const;
export type AreaName = (typeof AREAS)[number];
/** The area of class attributes and shared methods, which has no method declaration. */
export const ATTRIBUTES: AreaName = AREAS[0];

/** What one call of a function sees (GLOSSARY: Execution kind). */
export const EXECUTION_KINDS = ['Single value', 'Context', 'Queue'] as const;
export type ExecutionKind = (typeof EXECUTION_KINDS)[number];

/** The import instructions every source starts with: the UDF API (ADR-0020) and the allow-listed JDK packages. */
export const INITIAL_IMPORTS: readonly string[] = [
  'io.flottila.mapping.udf.*',
  'java.math.*',
  'java.text.*',
  'java.time.*',
  'java.util.*',
  'java.util.regex.*',
];

/** The Java types of a signature variable by its kind: arguments are strings, box parameters strings or ints (ADR-0020). */
export const JAVA_TYPES = { Argument: ['String'], Parameter: ['String', 'int'] } as const;
export type VariableKind = keyof typeof JAVA_TYPES;

export interface Archive {
  name: string;
  namespace: string;
}

/** The archives the Archives Used picker offers: synthetic examples only. */
export const ARCHIVE_EXAMPLES: readonly Archive[] = [
  ['JAVA_Audit', 'common:functions'],
  ['JAVA_ExampleServiceQuery', 'communication:components'],
  ['JAVA_ExampleMessagingApi', 'common:functions'],
  ['JAVA_ExampleUtilities', 'common:functions'],
  ['JAVA_Convert_XML2URLString', 'common:mappings'],
  ['JAVA_JSONXML', 'common:functions'],
  ['JAVA_XmlEnvelopedSignatureMapping', 'common:mappings'],
].map(([name, suffix]) => ({ name: name!, namespace: `urn:example:${suffix}` }));

/** One variable of a method's signature: an argument (an input of the box) or a box parameter. */
export interface Variable {
  id: string;
  kind: VariableKind;
  name: string;
  javaType: string;
  title: string;
  description?: string;
}

/** A user-defined function of the mapping (GLOSSARY: User-defined function). */
export interface Udf {
  id: string;
  name: string;
  title: string;
  category: string;
  description: string;
  execution: ExecutionKind;
  variables: Variable[];
  body: string;
}

/** A Java area's edited state; an area never edited has none. */
export interface JavaArea {
  body: string;
  description: string;
  variables: Variable[];
}

/** Everything the Functions tab keeps for one mapping. */
export interface FunctionsState {
  imports: string[];
  archives: Archive[];
  javaAreas: Partial<Record<AreaName, JavaArea>>;
  functions: Udf[];
}

export const emptyFunctions = (): FunctionsState => ({ imports: [...INITIAL_IMPORTS], archives: [], javaAreas: {}, functions: [] });

/** `prefix` with the smallest number from 1 up that makes a name not in `names`. */
export function unusedName(prefix: string, names: readonly string[]): string {
  let i = 1;
  while (names.includes(prefix + i)) i++;
  return prefix + i;
}

export function variable(kind: VariableKind, variables: readonly Variable[], id: string = crypto.randomUUID()): Variable {
  return { id, kind, name: unusedName('var', variables.map((v) => v.name)), javaType: 'String', title: '' };
}

export function newFunction(name: string, id: string = crypto.randomUUID()): Udf {
  return { id, name, title: name, category: 'User-Defined', description: '', execution: 'Single value', variables: [variable('Argument', [], 'in0')], body: '' };
}

/** A Java area as it is now: its edited state, or empty. */
export function builtIn(state: FunctionsState, name: AreaName): JavaArea & { name: AreaName } {
  return { name, body: '', description: '', variables: [], ...state.javaAreas[name] };
}

const KEYWORDS = new Set(
  (
    'abstract assert boolean break byte case catch char class const continue default do double else enum extends final ' +
    'finally float for goto if implements import instanceof int interface long native new package private protected ' +
    'public return short static strictfp super switch synchronized this throw throws transient try void volatile while ' +
    'true false null'
  ).split(' '),
);

/** A Java identifier from `text`: other characters become `_`, a leading digit gets a `_` before it, a keyword after it. */
function javaIdentifier(text: string): string {
  const word = text.replace(/[^\w$]/g, '_');
  if (/^\d/.test(word)) return `_${word}`;
  return KEYWORDS.has(word) ? `${word}_` : word;
}

/** The class and Java package of a mapping's functions: its name, and its mapping package's folders, if any. */
export function metadata(mappingId: string): { className: string; packageName: string } {
  const folders = mappingId.split('/');
  const name = folders.pop()!;
  return { className: javaIdentifier(name), packageName: folders.map(javaIdentifier).join('.') };
}

/** The opening line of a function's or lifecycle area's method, in ADR-0020's shape. */
export function declaration(fn: { name: string; variables: readonly Variable[]; execution?: ExecutionKind }): string {
  const lifecycle = fn.name === 'init' || fn.name === 'cleanUp';
  const execution = lifecycle ? 'Single value' : (fn.execution ?? 'Single value');
  const list = execution !== 'Single value';
  const args = fn.variables.map((v) =>
    v.kind === 'Parameter' ? `@Parameter("${v.name}") ${v.javaType} ${v.name}` : `${list ? execution : v.javaType} ${v.name}`,
  );
  if (list) args.push('ContextOut out');
  args.push('MappingRun run');
  return `public static ${list || lifecycle ? 'void' : 'String'} ${fn.name}(${args.join(', ')}) {`;
}

/** Why `value` cannot name a method or variable: not a Java identifier, or one of `taken`; empty when it can. */
export function nameIssue(value: string, taken: readonly string[]): string {
  if (!/^[a-zA-Z_$][\w$]*$/.test(value) || KEYWORDS.has(value)) return 'Enter a valid Java identifier.';
  return taken.includes(value) ? 'This name is already in use.' : '';
}

/** `rows` with the row at `from` swapped with the one `delta` away; `rows` itself when either is out of range. */
export function moveRow<T>(rows: readonly T[], from: number, delta: number): readonly T[] {
  const to = from + delta;
  if (from < 0 || from >= rows.length || to < 0 || to >= rows.length) return rows;
  const next = rows.slice();
  [next[from], next[to]] = [next[to]!, next[from]!];
  return next;
}

// --- reading what the browser kept: anything of another shape reads as no functions

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);
const isString = (value: unknown): value is string => typeof value === 'string';
const allOf = <T>(value: unknown, item: (entry: unknown) => entry is T): value is T[] => Array.isArray(value) && value.every(item);

function isVariable(value: unknown): value is Variable {
  return (
    isRecord(value) &&
    isString(value.id) &&
    (value.kind === 'Argument' || value.kind === 'Parameter') &&
    isString(value.name) &&
    isString(value.javaType) &&
    isString(value.title) &&
    (value.description === undefined || isString(value.description))
  );
}

function isUdf(value: unknown): value is Udf {
  return (
    isRecord(value) &&
    ['id', 'name', 'title', 'category', 'description', 'body'].every((key) => isString(value[key])) &&
    (EXECUTION_KINDS as readonly unknown[]).includes(value.execution) &&
    allOf(value.variables, isVariable)
  );
}

function isJavaArea(value: unknown): value is JavaArea {
  return isRecord(value) && isString(value.body) && isString(value.description) && allOf(value.variables, isVariable);
}

const isArchive = (value: unknown): value is Archive => isRecord(value) && isString(value.name) && isString(value.namespace);

/** The functions state `value` holds, or none when it is not one (absent, or written by another version). */
export function readFunctions(value: unknown): FunctionsState {
  if (
    isRecord(value) &&
    allOf(value.imports, isString) &&
    allOf(value.archives, isArchive) &&
    allOf(value.functions, isUdf) &&
    isRecord(value.javaAreas) &&
    Object.entries(value.javaAreas).every(([name, area]) => (AREAS as readonly string[]).includes(name) && isJavaArea(area))
  ) {
    return { imports: value.imports, archives: value.archives, functions: value.functions, javaAreas: value.javaAreas };
  }
  return emptyFunctions();
}
