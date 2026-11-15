import { DescriptionList, DescriptionListDescription, DescriptionListGroup, DescriptionListTerm, TextArea } from '@patternfly/react-core';
import type { ReactNode } from 'react';
import { useStore } from 'zustand';
import type { WorkingCopyStore } from '../store/working-copy';
import type { StructureRef } from '../../generated/mapping-model';
import { messageName } from '../shared/structure-names';
import './overview.css';

export interface MappingOverviewProps {
  /** The mapping's id, its workspace path: its folder is the package and its last segment the name (ADR-0029). */
  mappingId: string;
  store: WorkingCopyStore;
}

/** What a detail shows for an absent value, as the mapping list does: a package of the top folder, a message not chosen. */
const orDash = (value: string | undefined) => (value ? value : '—');

const nameOf = (ref: StructureRef | undefined) => orDash(ref && messageName(ref));

/**
 * The Overview tab (the prototype's MappingOverview): the mapping details in our terms, Name, Mapping ID, Package, Source
 * and Target (ADR-0028), and the working copy's description (GLOSSARY: Mapping description), read-only in view mode.
 */
export function MappingOverview({ mappingId, store }: MappingOverviewProps) {
  const editing = useStore(store, (s) => s.editing);
  const description = useStore(store, (s) => s.workingCopy.description ?? '');
  const source = useStore(store, (s) => s.workingCopy.source);
  const target = useStore(store, (s) => s.workingCopy.target);
  const slash = mappingId.lastIndexOf('/');
  const fields: [string, ReactNode][] = [
    ['Name', mappingId.slice(slash + 1)],
    ['Mapping ID', <code key="id">{mappingId}</code>],
    ['Package', orDash(mappingId.slice(0, Math.max(slash, 0)))],
    ['Source', nameOf(source)],
    ['Target', nameOf(target)],
  ];

  return (
    <section className="mapping-overview" aria-label="Overview">
      <div className="panel overview-properties">
        <h2>Mapping details</h2>
        <DescriptionList isHorizontal isCompact>
          {fields.map(([term, value]) => (
            <DescriptionListGroup key={term}>
              <DescriptionListTerm>{term}</DescriptionListTerm>
              <DescriptionListDescription>{value}</DescriptionListDescription>
            </DescriptionListGroup>
          ))}
        </DescriptionList>
      </div>
      <div className="panel overview-description">
        <label htmlFor="mapping-description">Description</label>
        <TextArea
          id="mapping-description"
          readOnlyVariant={editing ? undefined : 'default'}
          rows={8}
          resizeOrientation="vertical"
          value={description}
          onChange={(_, value) => store.getState().setDescription(value)}
        />
      </div>
    </section>
  );
}
