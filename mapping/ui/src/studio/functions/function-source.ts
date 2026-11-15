/**
 * Export Source Text and Import Source Text of the Functions tab (the prototype's functionSource.js): one Java class
 * whose editable sections sit between marker comments, so that a file edited elsewhere can be read back.
 */
import { AREAS, ATTRIBUTES, builtIn, declaration, metadata, type AreaName, type FunctionsState, type JavaArea, type Udf } from '../mock/function-model';

/** A section's marker: a stable hash of its identity, across saves in the browser and method renames. */
function marker(id: string): string {
  let hash = 2166136261;
  return Array.from({ length: 4 }, (_, round) => {
    for (const ch of id + round) hash = Math.imul(hash ^ ch.charCodeAt(0), 16777619);
    return (hash >>> 0).toString(16).padStart(8, '0');
  }).join('');
}

type Editable = (JavaArea & { name: AreaName; id?: undefined }) | Udf;

interface Section {
  key: string;
  tag: string;
  content: string;
  signature: string;
  fn?: Editable;
}

function sections(mappingId: string, state: FunctionsState): Section[] {
  const editable: Editable[] = [builtIn(state, AREAS[0]), builtIn(state, 'init'), ...state.functions, builtIn(state, 'cleanUp')];
  return [
    {
      key: 'import',
      tag: marker(`${mappingId}/imports`),
      content: state.imports
        .filter(Boolean)
        .map((s) => `import ${s};`)
        .join('\n'),
      signature: '',
    },
    ...editable.map((fn) => ({
      key: fn.name === ATTRIBUTES ? 'attributes and methods' : fn.name,
      tag: marker(`${mappingId}/${fn.id ?? fn.name}`),
      content: fn.body,
      signature: fn.name === ATTRIBUTES ? '' : declaration(fn),
      fn,
    })),
  ];
}

/** The Java source of a mapping's functions: its package, the import section and the class with every section. */
export function exportSource(mappingId: string, state: FunctionsState): string {
  const { className, packageName } = metadata(mappingId);
  const [imports, ...rest] = sections(mappingId, state);
  const block = (s: Section) => `// beginning of ${s.key} ${s.tag}\n${s.content}\n// end of ${s.key} ${s.tag}`;
  const methods = rest.map((s) => (s.signature ? `${s.signature}\n${block(s)}\n}` : block(s)));
  const header = packageName ? `package ${packageName};\n\n` : '';
  return `${header}${block(imports!)}\n\npublic class ${className} {\n${methods.join('\n\n')}\n}\n`;
}

/**
 * The state with the sections of `text`, a source exported for these functions: its import instructions and the body of
 * each area and function. Throws an `Error` that says what is wrong when the markers do not fit.
 */
export function importSource(mappingId: string, state: FunctionsState, text: string): FunctionsState {
  const all = sections(mappingId, state);
  const found = new Map<string, string>();
  const lines = text.replace(/^\uFEFF/, '').replace(/\r\n?/g, '\n').split('\n');
  let open: { name: string; tag: string } | null = null;
  let body: string[] = [];
  for (const line of lines) {
    const match = /^\s*\/\/ (beginning|end) of (.+?) ([a-f0-9]{32})\s*$/i.exec(line);
    if (!match) {
      if (open) body.push(line);
      continue;
    }
    const [, boundary, name, tag] = match as unknown as [string, string, string, string];
    if (boundary === 'beginning') {
      if (open || found.has(tag)) throw new Error('Overlapping or duplicate source sections.');
      open = { name, tag };
      body = [];
    } else {
      if (open?.tag !== tag || open.name !== name) throw new Error('Source section markers do not match.');
      found.set(tag, body.join('\n'));
      open = null;
    }
  }
  if (open || !all.every((s) => found.has(s.tag)) || all.length !== found.size) {
    throw new Error('Choose a source file exported for these functions. Check its section markers.');
  }
  const imports = found
    .get(all[0]!.tag)!
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean)
    .map((s) => {
      const match = /^import\s+([\w$.*]+);$/.exec(s);
      if (!match) throw new Error(`Invalid import instruction: ${s}`);
      return match[1]!;
    });
  const javaAreas = { ...state.javaAreas };
  const functions = state.functions.map((f) => ({ ...f }));
  for (const s of all.slice(1)) {
    const fn = s.fn!;
    const text = found.get(s.tag)!;
    const udf = functions.find((f) => f.id === fn.id);
    if (udf) udf.body = text;
    else javaAreas[fn.name as AreaName] = { body: text, description: fn.description, variables: fn.variables };
  }
  return { ...state, imports, javaAreas, functions };
}
