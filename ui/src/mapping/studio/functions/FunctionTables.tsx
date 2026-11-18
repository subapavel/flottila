import {
  Button,
  Card,
  CardBody,
  FormSelect,
  FormSelectOption,
  HelperText,
  HelperTextItem,
  Stack,
  StackItem,
  TextInput,
  Title,
  ToggleGroup,
  ToggleGroupItem,
  Toolbar,
  ToolbarContent,
  ToolbarItem,
} from '@patternfly/react-core';
import { ArrowDownIcon, ArrowUpIcon, CopyIcon, CutIcon, PasteIcon, PlusIcon, SearchIcon, TrashIcon } from '@patternfly/react-icons';
import { Table, Tbody, Td, Th, Thead, Tr } from '@patternfly/react-table';
import { useId, useState, type ReactNode } from 'react';
import { INITIAL_IMPORTS, JAVA_TYPES, moveRow, nameIssue, variable, type Archive, type FunctionsState, type Udf, type Variable, type VariableKind } from '../mock/function-model';
import { Tool } from '../shared/Tool';
import { ArchiveCatalog } from './ArchiveCatalog';

/** Insert, Delete, Move up and Move down for the rows of a table, and any further tool items after a separator. */
export function RowTools(props: {
  label: string;
  onAdd: () => void;
  onRemove: () => void;
  onMove: (delta: number) => void;
  canRemove: boolean;
  canUp: boolean;
  canDown: boolean;
  disabled?: boolean;
  children?: ReactNode;
}) {
  const { label, onAdd, onRemove, onMove, canRemove, canUp, canDown, disabled = false, children } = props;
  return (
    <Toolbar role="toolbar" aria-label={label}>
      <ToolbarContent>
        <ToolbarItem>
          <Tool label="Insert row" icon={PlusIcon} disabled={disabled} onClick={onAdd} />
        </ToolbarItem>
        <ToolbarItem>
          <Tool label="Delete row" icon={TrashIcon} disabled={disabled || !canRemove} onClick={onRemove} />
        </ToolbarItem>
        <ToolbarItem variant="separator" />
        <ToolbarItem>
          <Tool label="Move up" icon={ArrowUpIcon} disabled={disabled || !canUp} onClick={() => onMove(-1)} />
        </ToolbarItem>
        <ToolbarItem>
          <Tool label="Move down" icon={ArrowDownIcon} disabled={disabled || !canDown} onClick={() => onMove(1)} />
        </ToolbarItem>
        {children && (
          <>
            <ToolbarItem variant="separator" />
            {children}
          </>
        )}
      </ToolbarContent>
    </Toolbar>
  );
}

/**
 * A name field that commits on blur or Enter when the name is a Java identifier not in `taken`, and says why not
 * otherwise; Escape drops the draft.
 */
export function NameEditor(props: { id?: string; value: string; label: string; taken: readonly string[]; disabled?: boolean; onCommit: (name: string) => void }) {
  const { id, value, label, taken, disabled, onCommit } = props;
  const [draft, setDraft] = useState<string | null>(null);
  const [error, setError] = useState('');
  const helper = useId();
  function commit() {
    if (draft === null) return;
    const name = draft.trim();
    const issue = nameIssue(name, taken);
    if (issue) {
      setError(issue);
      return;
    }
    onCommit(name);
    setDraft(null);
    setError('');
  }
  return (
    <>
      <TextInput
        id={id}
        aria-label={label}
        validated={error ? 'error' : 'default'}
        aria-describedby={error ? helper : undefined}
        isDisabled={disabled}
        value={draft ?? value}
        onChange={(_, next) => {
          setError('');
          setDraft(next);
        }}
        onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === 'Enter') e.currentTarget.blur();
          if (e.key === 'Escape') {
            setDraft(null);
            setError('');
          }
        }}
      />
      {error && (
        <HelperText id={helper} role="alert">
          <HelperTextItem variant="error">{error}</HelperTextItem>
        </HelperText>
      )}
    </>
  );
}

/** The signature variables of a function or lifecycle area; a lifecycle area has box parameters only. */
export function Variables({ fn, onChange, lifecycle, disabled }: { fn: Pick<Udf, 'variables'>; onChange: (variables: Variable[]) => void; lifecycle: boolean; disabled: boolean }) {
  const [selected, select] = useState<string | null>(null);
  const rows = fn.variables;
  const index = rows.findIndex((v) => v.id === selected);
  const patch = (id: string, changes: Partial<Variable>) => onChange(rows.map((v) => (v.id === id ? { ...v, ...changes } : v)));
  function add() {
    const v = variable(lifecycle ? 'Parameter' : 'Argument', rows);
    const at = index < 0 ? rows.length : index + 1;
    onChange([...rows.slice(0, at), v, ...rows.slice(at)]);
    select(v.id);
  }
  const names = rows.map((r) => r.name);
  const headings = ['Type', 'Name', 'Java Type', 'Title', ...(lifecycle ? [] : ['Description'])];
  return (
    <Stack role="group" aria-label="Signature Variables">
      <StackItem>
        <Title headingLevel="h3" size="md">
          Signature Variables
        </Title>
      </StackItem>
      <StackItem>
        <RowTools
          label="Variable actions"
          disabled={disabled}
          onAdd={add}
          canRemove={index >= 0}
          canUp={index > 0}
          canDown={index >= 0 && index < rows.length - 1}
          onRemove={() => {
            onChange(rows.filter((v) => v.id !== selected));
            select(null);
          }}
          onMove={(delta) => onChange([...moveRow(rows, index, delta)])}
        />
      </StackItem>
      <StackItem>
        {/* PatternFly gives a Table the role grid; this one has no grid keyboard navigation: it is a plain table */}
        <Table aria-label="Signature variables" role="table" variant="compact">
          <Thead>
            <Tr>
              {headings.map((s) => (
                <Th key={s} modifier={s === 'Type' || s === 'Java Type' ? 'fitContent' : undefined}>
                  {s}
                </Th>
              ))}
            </Tr>
          </Thead>
          <Tbody>
            {rows.map((v) => (
              <Tr key={v.id} isRowSelected={v.id === selected} onFocus={() => select(v.id)} onClick={() => select(v.id)}>
                <Td dataLabel="Type">
                  {lifecycle ? (
                    'Parameter'
                  ) : (
                    <FormSelect
                      aria-label="Variable kind"
                      value={v.kind}
                      isDisabled={disabled}
                      onChange={(_, value) => {
                        const kind = value as VariableKind;
                        patch(v.id, { kind, javaType: (JAVA_TYPES[kind] as readonly string[]).includes(v.javaType) ? v.javaType : 'String' });
                      }}
                    >
                      {Object.keys(JAVA_TYPES).map((k) => (
                        <FormSelectOption key={k} value={k} label={k} />
                      ))}
                    </FormSelect>
                  )}
                </Td>
                <Td dataLabel="Name">
                  <NameEditor
                    label="Variable name"
                    value={v.name}
                    disabled={disabled}
                    taken={['run', 'out', ...names.filter((n) => n !== v.name)]}
                    onCommit={(name) => patch(v.id, { name })}
                  />
                </Td>
                <Td dataLabel="Java Type">
                  <FormSelect aria-label="Java type" value={v.javaType} isDisabled={disabled} onChange={(_, javaType) => patch(v.id, { javaType })}>
                    {JAVA_TYPES[v.kind].map((t) => (
                      <FormSelectOption key={t} value={t} label={t} />
                    ))}
                  </FormSelect>
                </Td>
                <Td dataLabel="Title">
                  <TextInput aria-label="Variable title" value={v.title} isDisabled={disabled} onChange={(_, title) => patch(v.id, { title })} />
                </Td>
                {!lifecycle && (
                  <Td dataLabel="Description">
                    <TextInput aria-label="Variable description" value={v.description ?? ''} isDisabled={disabled} onChange={(_, description) => patch(v.id, { description })} />
                  </Td>
                )}
              </Tr>
            ))}
          </Tbody>
        </Table>
      </StackItem>
    </Stack>
  );
}

const DEPENDENCY_VIEWS = ['Import Instructions', 'Archives Used'] as const;
const EMPTY_ARCHIVE: Archive = { name: '', namespace: '' };

/**
 * The import instructions and archives of the mapping's functions: the initial import instructions are fixed, the ones
 * added after them editable; an archive and its namespace are chosen from the catalogue, and an archive row can be cut,
 * copied and pasted.
 */
export function Dependencies({ state, edit, disabled }: { state: FunctionsState; edit: (change: (current: FunctionsState) => FunctionsState) => void; disabled: boolean }) {
  const [view, setView] = useState<(typeof DEPENDENCY_VIEWS)[number]>('Import Instructions');
  const [index, select] = useState(-1);
  const [picker, setPicker] = useState<{ row: number; kind: 'archive' | 'namespace' } | null>(null);
  const [clip, setClip] = useState<Archive | null>(null);
  const archive = view === 'Archives Used';
  const rows: readonly (string | Archive)[] = archive ? state.archives : state.imports;
  const valid = index >= 0 && index < rows.length;
  const editable = valid && (archive || index >= INITIAL_IMPORTS.length);
  const write = (list: readonly (string | Archive)[]) => edit((current) => (archive ? { ...current, archives: list as Archive[] } : { ...current, imports: list as string[] }));
  const replace = (at: number, value: string | Archive) => write(rows.map((r, i) => (i === at ? value : r)));
  function add() {
    const at = editable ? index + 1 : rows.length;
    write([...rows.slice(0, at), archive ? EMPTY_ARCHIVE : '', ...rows.slice(at)]);
    select(at);
    if (!archive) requestAnimationFrame(() => document.querySelector<HTMLElement>(`[aria-label="Import instruction ${at + 1}"]`)?.focus());
  }
  function move(delta: number) {
    write(moveRow(rows, index, delta));
    select(index + delta);
  }
  const current = state.archives[index];
  return (
    <Card component="section" aria-label="Function dependencies">
      <CardBody>
        <Stack hasGutter>
          <StackItem>
            <ToggleGroup isCompact aria-label="Function dependencies view">
              {DEPENDENCY_VIEWS.map((t) => (
                <ToggleGroupItem
                  key={t}
                  text={t}
                  isSelected={view === t}
                  onChange={() => {
                    setView(t);
                    select(-1);
                  }}
                />
              ))}
            </ToggleGroup>
          </StackItem>
          <StackItem>
            <RowTools
              label={archive ? 'Archive actions' : 'Import actions'}
              disabled={disabled}
              onAdd={add}
              canRemove={editable}
              canUp={editable && index > (archive ? 0 : INITIAL_IMPORTS.length)}
              canDown={editable && index < rows.length - 1}
              onRemove={() => {
                write(rows.filter((_, i) => i !== index));
                select(-1);
              }}
              onMove={move}
            >
              {archive && (
                <>
                  <ToolbarItem>
                    <Tool
                      label="Cut Object"
                      icon={CutIcon}
                      disabled={disabled || !current?.name}
                      onClick={() => {
                        setClip(current!);
                        replace(index, EMPTY_ARCHIVE);
                      }}
                    />
                  </ToolbarItem>
                  <ToolbarItem>
                    <Tool label="Copy Object" icon={CopyIcon} disabled={!current?.name} onClick={() => setClip(current!)} />
                  </ToolbarItem>
                  <ToolbarItem>
                    <Tool label="Paste Object" icon={PasteIcon} disabled={disabled || !current || !clip} onClick={() => replace(index, { ...clip! })} />
                  </ToolbarItem>
                </>
              )}
            </RowTools>
          </StackItem>
          <StackItem>
            <Table aria-label={view} role="table" variant="compact">
              <Thead>
                <Tr>
                  <Th>{archive ? 'Name' : 'import'}</Th>
                  {archive && <Th>Namespace</Th>}
                </Tr>
              </Thead>
              <Tbody>
                {rows.map((r, i) => (
                  <Tr key={i} isRowSelected={index === i} onClick={() => select(i)} onFocus={() => select(i)}>
                    {typeof r === 'string' ? (
                      <Td dataLabel="import">
                        {i < INITIAL_IMPORTS.length ? (
                          <code>{r}</code>
                        ) : (
                          <TextInput
                            readOnlyVariant={disabled ? 'default' : undefined}
                            aria-label={`Import instruction ${i + 1}`}
                            placeholder="package.Class"
                            value={r}
                            onChange={(_, value) => replace(i, value)}
                          />
                        )}
                      </Td>
                    ) : (
                      (['name', 'namespace'] as const).map((field) => (
                        <Td key={field} dataLabel={field === 'name' ? 'Name' : 'Namespace'}>
                          <Button
                            variant="link"
                            isInline
                            icon={<SearchIcon />}
                            iconPosition="end"
                            isDisabled={disabled}
                            aria-label={`Choose archive ${field} ${i + 1}`}
                            onClick={() => setPicker({ row: i, kind: field === 'name' ? 'archive' : 'namespace' })}
                          >
                            {r[field] || 'Choose…'}
                          </Button>
                        </Td>
                      ))
                    )}
                  </Tr>
                ))}
              </Tbody>
            </Table>
          </StackItem>
        </Stack>
      </CardBody>
      {picker && (
        <ArchiveCatalog
          kind={picker.kind}
          close={() => setPicker(null)}
          onApply={(entry) => {
            replace(picker.row, picker.kind === 'namespace' ? { ...state.archives[picker.row]!, namespace: entry.name } : entry);
            setPicker(null);
          }}
        />
      )}
    </Card>
  );
}
