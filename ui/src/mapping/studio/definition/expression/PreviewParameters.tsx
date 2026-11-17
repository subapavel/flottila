import {
  Button,
  Content,
  DescriptionList,
  DescriptionListDescription,
  DescriptionListGroup,
  DescriptionListTerm,
  EmptyState,
  Flex,
  FlexItem,
  Form,
  FormGroup,
  FormSelect,
  FormSelectOption,
  Grid,
  GridItem,
  Modal,
  ModalBody,
  ModalFooter,
  ModalHeader,
  Radio,
  SearchInput,
  SimpleList,
  SimpleListItem,
  Switch,
  TextInput,
  Title,
} from '@patternfly/react-core';
import { ArrowDownIcon, ArrowUpIcon, TrashIcon } from '@patternfly/react-icons';
import { Table, Tbody, Td, Th, Thead, Tr } from '@patternfly/react-table';
import { useState, type FormEvent } from 'react';
import type { PreviewParam } from '../../mock/definition-preview';
import { initialParams, RFC_MODULE, RFC_NAMESPACE, type PreviewFunction, type PreviewFunctionParameter } from '../../mock/preview-functions';
import { moveRow } from '../../mock/signature-mock';
import { Tool } from '../../shared/Tool';

type Values = Record<string, PreviewParam>;

/** The choices of Behaviour if lookup fails. */
const STRATEGIES = [
  ['2', 'Use Key'],
  ['1', 'Use Default Value'],
  ['0', 'Throw Exception'],
] as const;

const TITLES: Record<PreviewFunction['key'], string> = { valuemap: 'Value Mapping Properties', tableLookup: 'JDBC Lookup', rfclookup: 'RFC Lookup' };

/** A field of the Value Mapping Properties: a text, an agency with its default offered, or the strategy. */
function Field({ param, value, change, disabled }: { param: PreviewFunctionParameter; value: string; change: (value: string) => void; disabled: boolean }) {
  const { key, label, type } = param;
  const id = `preview-param-${key}`;
  if (type === 'vmstrategy') {
    return (
      <FormGroup label={label} role="radiogroup" isStack fieldId={id}>
        {STRATEGIES.map(([choice, name]) => (
          <Radio key={choice} id={`${id}-${choice}`} name={id} label={name} isChecked={value === choice} isDisabled={disabled} onChange={() => change(choice)} />
        ))}
      </FormGroup>
    );
  }
  const agency = type === 'vmsagency' ? 'SenderParty' : type === 'vmtagency' ? 'ReceiverParty' : null;
  return (
    <FormGroup label={label} fieldId={id}>
      <TextInput id={id} value={value} isDisabled={disabled} list={agency ? `${id}-agency` : undefined} onChange={(_, next) => change(next)} />
      {agency && (
        <datalist id={`${id}-agency`}>
          <option value={agency} />
        </datalist>
      )}
    </FormGroup>
  );
}

/** A list of a lookup's fields: reorder or remove the selected field. */
function FieldList({ title, fields, change, disabled }: { title: string; fields: string[]; change: (fields: string[]) => void; disabled: boolean }) {
  const [selected, setSelected] = useState('');
  const index = fields.indexOf(selected);
  return (
    <FormGroup label={title} role="group" fieldId={`field-list-${title}`}>
      {fields.length === 0 ? (
        <Content component="small">No fields</Content>
      ) : (
        <SimpleList aria-label={title} onSelect={(_, props) => setSelected(String(props.itemId))}>
          {fields.map((name) => (
            <SimpleListItem key={name} itemId={name} isActive={name === selected}>
              {name}
            </SimpleListItem>
          ))}
        </SimpleList>
      )}
      <Flex gap={{ default: 'gapNone' }}>
        <Tool label={`Move ${title} up`} icon={ArrowUpIcon} disabled={disabled || index <= 0} onClick={() => change([...moveRow(fields, index, -1)])} />
        <Tool label={`Move ${title} down`} icon={ArrowDownIcon} disabled={disabled || index < 0 || index >= fields.length - 1} onClick={() => change([...moveRow(fields, index, 1)])} />
        <Tool label={`Remove selected field from ${title}`} icon={TrashIcon} disabled={disabled || index < 0} onClick={() => change(fields.filter((n) => n !== selected))} />
      </Flex>
    </FormGroup>
  );
}

/** The lookup picker: the lookup catalogue, the one RFC module it offers, no database table. */
function LookupPicker({ rfc, current, apply, close }: { rfc: boolean; current: string; apply: (ref: string) => void; close: () => void }) {
  const [selected, setSelected] = useState('');
  const [query, setQuery] = useState('');
  const entries = [...new Set([...(rfc ? [RFC_MODULE] : []), ...(current ? [current] : [])])];
  const rows = entries.filter((n) => n.toLowerCase().includes(query.trim().toLowerCase()));
  const title = rfc ? 'Choose Function Module' : 'Choose External Definition';
  return (
    <Modal isOpen variant="medium" aria-label={title} onClose={close}>
      <ModalHeader title={title} />
      <ModalBody>
        <Title headingLevel="h3" size="md">
          Search Criteria
        </Title>
        <DescriptionList isHorizontal isCompact>
          <DescriptionListGroup>
            <DescriptionListTerm>Category</DescriptionListTerm>
            <DescriptionListDescription>{rfc ? 'rfc' : 'dbtab'}</DescriptionListDescription>
          </DescriptionListGroup>
        </DescriptionList>
        <Title headingLevel="h3" size="md">
          Search Result
        </Title>
        <SearchInput aria-label="Search" placeholder="Search" value={query} onChange={(_, value) => setQuery(value)} onClear={() => setQuery('')} />
        <Table aria-label="Search Result" variant="compact" role="table">
          <Thead>
            <Tr>
              <Th>Name</Th>
              <Th>Namespace</Th>
            </Tr>
          </Thead>
          <Tbody>
            {rows.map((n) => (
              <Tr key={n} isRowSelected={selected === n}>
                <Td dataLabel="Name">
                  <Button variant="link" isInline aria-pressed={selected === n} onClick={() => setSelected(n)}>
                    {n}
                  </Button>
                </Td>
                <Td dataLabel="Namespace">{n === RFC_MODULE ? RFC_NAMESPACE : '—'}</Td>
              </Tr>
            ))}
          </Tbody>
        </Table>
        {rows.length === 0 && <EmptyState titleText="No Suitable Data Found" headingLevel="h4" variant="xs" />}
      </ModalBody>
      <ModalFooter>
        <Button isDisabled={!rows.includes(selected)} onClick={() => apply(selected)}>
          Apply
        </Button>
        <Button variant="link" onClick={close}>
          Cancel
        </Button>
      </ModalFooter>
    </Modal>
  );
}

interface LookupParametersProps {
  fn: PreviewFunction;
  draft: Values;
  change: (key: string, value: PreviewParam) => void;
  openPicker: () => void;
  disabled: boolean;
}

/** A lookup's parameters: the channel, the table or module, its field lists and its switch. */
function LookupParameters({ fn, draft, change, openPicker, disabled }: LookupParametersProps) {
  const rfc = fn.key === 'rfclookup';
  const flag = rfc ? 'useExc' : 'split';
  const flagSwitch = (
    <Switch
      id={`lookup-${flag}`}
      label={rfc ? 'Use Exception' : 'Insert Context Change After Each Result Value'}
      isChecked={draft[flag] as boolean}
      isDisabled={disabled}
      onChange={(_, checked) => change(flag, checked)}
    />
  );
  const ref = draft.ref as string;
  return (
    <>
      <FormGroup label="Communication Channel" fieldId="lookup-channel">
        {/* the mapping parameters have no Adapter category yet: no channel to offer */}
        <FormSelect id="lookup-channel" value="" isDisabled>
          <FormSelectOption value="" label="No suitable parameter found; define new parameter of type 'Channel' first" />
        </FormSelect>
      </FormGroup>
      <Flex alignItems={{ default: 'alignItemsCenter' }}>
        <FlexItem>{ref || (rfc ? 'Select an RFC module' : 'Select a database table')}</FlexItem>
        <FlexItem>
          <Button variant="secondary" size="sm" isDisabled={disabled} onClick={openPicker}>
            {rfc ? 'Choose RFC module' : 'Choose database table'}
          </Button>
        </FlexItem>
      </Flex>
      {!rfc && flagSwitch}
      <Grid hasGutter md={rfc ? 6 : 4}>
        <GridItem>
          <FieldList title={rfc ? 'Import fields' : 'Input fields'} fields={draft.ins as string[]} change={(v) => change('ins', v)} disabled={disabled} />
        </GridItem>
        {!rfc && (
          <GridItem>
            <FormGroup label="Database table fields" role="group" fieldId="lookup-table-fields">
              <Content component="small">No fields available</Content>
            </FormGroup>
          </GridItem>
        )}
        <GridItem>
          <FieldList title={rfc ? 'Export fields' : 'Output fields'} fields={draft.outs as string[]} change={(v) => change('outs', v)} disabled={disabled} />
        </GridItem>
      </Grid>
      {rfc && flagSwitch}
    </>
  );
}

export interface PreviewParametersProps {
  fn: PreviewFunction;
  /** The node's parameters as the preview keeps them: those that differ from the initial values. */
  values: Values | undefined;
  readOnly: boolean;
  /** Saves the parameters that differ from the initial values. */
  save: (params: Values) => void;
  close: () => void;
}

/**
 * The parameter dialog of a preview function (Value mapping and the lookups): the Value Mapping Properties, or a
 * lookup's channel, table or module and fields. Apply saves them to the preview; in view mode it only shows them.
 */
export function PreviewParameters({ fn, values, readOnly, save, close }: PreviewParametersProps) {
  const initial = initialParams(fn);
  const [draft, setDraft] = useState<Values>(() => ({ ...initial, ...values }));
  const [picking, setPicking] = useState(false);
  const change = (key: string, value: PreviewParam) => setDraft((d) => ({ ...d, [key]: value }));
  const lookup = fn.key !== 'valuemap';
  if (picking) {
    return (
      <LookupPicker
        rfc={fn.key === 'rfclookup'}
        current={draft.ref as string}
        close={() => setPicking(false)}
        apply={(ref) => {
          setDraft((d) => ({ ...d, ref, ins: [], outs: [] }));
          setPicking(false);
        }}
      />
    );
  }
  const submit = (e: FormEvent) => {
    e.preventDefault();
    save(Object.fromEntries(Object.entries(draft).filter(([key, value]) => JSON.stringify(value) !== JSON.stringify(initial[key]))));
  };
  return (
    <Modal isOpen onClose={close} variant={lookup ? 'medium' : 'small'} aria-label={TITLES[fn.key]}>
      <ModalHeader title={TITLES[fn.key]} />
      <ModalBody>
        <Form id="preview-parameters" onSubmit={submit}>
          {lookup ? (
            <LookupParameters fn={fn} draft={draft} change={change} openPicker={() => setPicking(true)} disabled={readOnly} />
          ) : (
            fn.parameters.map((p) => <Field key={p.key} param={p} value={draft[p.key] as string} change={(v) => change(p.key, v)} disabled={readOnly} />)
          )}
        </Form>
      </ModalBody>
      <ModalFooter>
        {!readOnly && (
          <Button type="submit" form="preview-parameters">
            Apply
          </Button>
        )}
        <Button variant="link" onClick={close}>
          {readOnly ? 'Close' : 'Cancel'}
        </Button>
      </ModalFooter>
    </Modal>
  );
}
