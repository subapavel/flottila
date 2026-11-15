import { Button } from '@patternfly/react-core';
import { RedoIcon, SearchIcon } from '@patternfly/react-icons';
import { useRef, useState, type FormEvent } from 'react';
import { ARCHIVE_EXAMPLES, type Archive } from '../mock/function-model';
import { Dialog, Tool } from './ToolbarTools';

/**
 * The picker of an archive or an archive namespace for Archives Used: the tabular search help of the prototype's
 * SignatureCatalog, without its search criteria and hierarchical help, whose root is a software component version we do
 * not have. Go moves to the next row that contains the search text; a click selects a row, a double click applies it.
 */
export function ArchiveCatalog({ kind, onApply, close }: { kind: 'archive' | 'namespace'; onApply: (entry: Archive) => void; close: () => void }) {
  const namespaces = kind === 'namespace';
  const rows: readonly Archive[] = namespaces ? [...new Set(ARCHIVE_EXAMPLES.map((r) => r.namespace))].map((name) => ({ name, namespace: name })) : ARCHIVE_EXAMPLES;
  const columns: [string, keyof Archive][] = namespaces ? [['Namespace', 'name']] : [['Name', 'name'], ['Namespace', 'namespace']];
  const [query, setQuery] = useState('');
  const [selected, setSelected] = useState('');
  const [hit, setHit] = useState('');
  const [notice, setNotice] = useState('');
  const table = useRef<HTMLTableElement>(null);
  const chosen = rows.find((r) => r.name === selected);
  function go(event: FormEvent) {
    event.preventDefault();
    const q = query.trim().toLowerCase();
    const matches = rows.filter((r) => columns.some(([, key]) => r[key].toLowerCase().includes(q)));
    if (!matches.length) {
      setHit('');
      setNotice('No match.');
      return;
    }
    const next = matches[(matches.findIndex((r) => r.name === hit) + 1) % matches.length]!;
    setHit(next.name);
    setNotice(`Match: ${next.name}`);
    [...table.current!.rows].find((tr) => tr.dataset.name === next.name)?.scrollIntoView({ block: 'nearest' });
  }
  function refresh() {
    setQuery('');
    setSelected('');
    setHit('');
    setNotice('');
  }
  return (
    <Dialog
      wide
      title={namespaces ? 'Choose Namespace' : 'Choose Imported Archive'}
      close={close}
      footer={
        <>
          <Button isDisabled={!chosen} onClick={() => onApply(chosen!)}>
            Apply
          </Button>
          <Button variant="link" onClick={close}>
            Cancel
          </Button>
        </>
      }
    >
      <h3 className="sig-result-title">Search Result</h3>
      <form className="sig-search" onSubmit={go}>
        <Tool label="Refresh results" icon={RedoIcon} onClick={refresh} />
        <label>
          <SearchIcon />
          <input
            autoFocus
            aria-label="Search catalog"
            placeholder="Find in results"
            value={query}
            onChange={(e) => {
              setQuery(e.target.value);
              setHit('');
              setNotice('');
            }}
          />
        </label>
        <Button type="submit" size="sm" variant="secondary" isDisabled={!query.trim()}>
          Go
        </Button>
      </form>
      <div className="sig-catalog-scroll">
        <table ref={table} className="editor-table sig-catalog" aria-label="Catalog results">
          <thead>
            <tr>
              {columns.map(([h]) => (
                <th key={h}>{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr
                key={r.name}
                data-name={r.name}
                className={r.name === selected ? 'sig-selected' : r.name === hit ? 'sig-found' : ''}
                onClick={() => setSelected(r.name)}
                onDoubleClick={() => onApply(r)}
              >
                {columns.map(([h, k], i) => (
                  <td key={h}>
                    {i === 0 ? (
                      <button type="button" className="sig-result-name" aria-pressed={r.name === selected} onClick={() => setSelected(r.name)}>
                        {r[k]}
                      </button>
                    ) : (
                      r[k]
                    )}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="sig-find-status" role="status">
        {notice}
      </p>
    </Dialog>
  );
}
