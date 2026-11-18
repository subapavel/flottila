import { Button, Content, Form, Label, Modal, ModalBody, ModalFooter, ModalHeader, Split, SplitItem, TextInput, Title } from '@patternfly/react-core';
import { RedoIcon, SearchIcon } from '@patternfly/react-icons';
import { Table, Tbody, Td, Th, Thead, Tr } from '@patternfly/react-table';
import { useId, useRef, useState, type FormEvent } from 'react';
import { ARCHIVE_EXAMPLES, type Archive } from '../mock/function-model';
import { Tool } from '../shared/Tool';

/**
 * The picker of an archive or an archive namespace for Archives Used: a tabular search help, without search criteria
 * and without a hierarchical help, whose root would be a software component version, which the studio does not have. Go
 * moves to the next row that contains the search text; a click selects a row, a double click applies it.
 */
export function ArchiveCatalog({ kind, onApply, close }: { kind: 'archive' | 'namespace'; onApply: (entry: Archive) => void; close: () => void }) {
  const namespaces = kind === 'namespace';
  const rows: readonly Archive[] = namespaces ? [...new Set(ARCHIVE_EXAMPLES.map((r) => r.namespace))].map((name) => ({ name, namespace: name })) : ARCHIVE_EXAMPLES;
  const columns: [string, keyof Archive][] = namespaces
    ? [['Namespace', 'name']]
    : [
        ['Name', 'name'],
        ['Namespace', 'namespace'],
      ];
  const [query, setQuery] = useState('');
  const [selected, setSelected] = useState('');
  const [hit, setHit] = useState('');
  const [notice, setNotice] = useState('');
  const table = useRef<HTMLTableElement>(null);
  const title = useId();
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
    <Modal variant="medium" isOpen onClose={close} aria-labelledby={title}>
      <ModalHeader title={namespaces ? 'Choose Namespace' : 'Choose Imported Archive'} labelId={title} />
      <ModalBody>
        <Title headingLevel="h3" size="md">
          Search Result
        </Title>
        <Form onSubmit={go}>
          <Split hasGutter>
            <SplitItem>
              <Tool label="Refresh results" icon={RedoIcon} onClick={refresh} />
            </SplitItem>
            <SplitItem isFilled>
              <TextInput
                autoFocus
                aria-label="Search catalog"
                placeholder="Find in results"
                customIcon={<SearchIcon />}
                value={query}
                onChange={(_, value) => {
                  setQuery(value);
                  setHit('');
                  setNotice('');
                }}
              />
            </SplitItem>
            <SplitItem>
              <Button type="submit" size="sm" variant="secondary" isDisabled={!query.trim()}>
                Go
              </Button>
            </SplitItem>
          </Split>
        </Form>
        {/* PatternFly gives a Table the role grid; this one has no grid keyboard navigation: it is a plain table */}
        <Table ref={table} aria-label="Catalog results" role="table" variant="compact">
          <Thead>
            <Tr>
              {columns.map(([h]) => (
                <Th key={h}>{h}</Th>
              ))}
            </Tr>
          </Thead>
          <Tbody>
            {rows.map((r) => (
              <Tr key={r.name} data-name={r.name} isRowSelected={r.name === selected} onClick={() => setSelected(r.name)} onDoubleClick={() => onApply(r)}>
                {columns.map(([h, k], i) => (
                  <Td key={h} dataLabel={h}>
                    {i === 0 ? (
                      <>
                        <Button variant="link" isInline aria-pressed={r.name === selected} onClick={() => setSelected(r.name)}>
                          {r[k]}
                        </Button>
                        {r.name === hit && (
                          <>
                            {' '}
                            <Label isCompact color="blue">
                              Match
                            </Label>
                          </>
                        )}
                      </>
                    ) : (
                      r[k]
                    )}
                  </Td>
                ))}
              </Tr>
            ))}
          </Tbody>
        </Table>
        <Content component="p" role="status">
          {notice}
        </Content>
      </ModalBody>
      <ModalFooter>
        <Button isDisabled={!chosen} onClick={() => onApply(chosen!)}>
          Apply
        </Button>
        <Button variant="link" onClick={close}>
          Cancel
        </Button>
      </ModalFooter>
    </Modal>
  );
}
