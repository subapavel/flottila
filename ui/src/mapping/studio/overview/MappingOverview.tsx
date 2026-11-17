import {
  Card,
  CardBody,
  CardTitle,
  DescriptionList,
  DescriptionListDescription,
  DescriptionListGroup,
  DescriptionListTerm,
  FormGroup,
  Grid,
  GridItem,
  TextArea,
  Title,
} from '@patternfly/react-core';
import { useId, type ReactNode } from 'react';
import { useStore } from 'zustand';
import type { StructureRef } from '../../generated/mapping-model';
import { messageName } from '../shared/structure-names';
import type { WorkingCopyStore } from '../store/working-copy';

export interface MappingOverviewProps {
  /** The mapping's id, its workspace path: its folder is the package and its last segment the name. */
  mappingId: string;
  store: WorkingCopyStore;
}

/** What a detail shows for an absent value, as the mapping list does: a package of the top folder, a message not chosen. */
const orDash = (value: string | undefined) => (value ? value : '—');

const nameOf = (ref: StructureRef | undefined) => orDash(ref && messageName(ref));

/**
 * The Overview tab: two cards side by side, one below the other on a narrow screen. The first holds the mapping
 * details, Name, Mapping ID, Package, Source and Target; the second the working copy's description, read-only in
 * view mode.
 */
export function MappingOverview({ mappingId, store }: MappingOverviewProps) {
  const editing = useStore(store, (s) => s.editing);
  const description = useStore(store, (s) => s.workingCopy.description ?? '');
  const source = useStore(store, (s) => s.workingCopy.source);
  const target = useStore(store, (s) => s.workingCopy.target);
  const descriptionId = useId();
  const slash = mappingId.lastIndexOf('/');
  const fields: [string, ReactNode][] = [
    ['Name', mappingId.slice(slash + 1)],
    ['Mapping ID', <code key="id">{mappingId}</code>],
    ['Package', orDash(mappingId.slice(0, Math.max(slash, 0)))],
    ['Source', nameOf(source)],
    ['Target', nameOf(target)],
  ];

  return (
    <Grid hasGutter component="section" aria-label="Overview">
      <GridItem lg={5}>
        <Card isFullHeight>
          <CardTitle>
            <Title headingLevel="h2" size="lg">
              Mapping details
            </Title>
          </CardTitle>
          <CardBody>
            <DescriptionList isHorizontal isCompact>
              {fields.map(([term, value]) => (
                <DescriptionListGroup key={term}>
                  <DescriptionListTerm>{term}</DescriptionListTerm>
                  <DescriptionListDescription>{value}</DescriptionListDescription>
                </DescriptionListGroup>
              ))}
            </DescriptionList>
          </CardBody>
        </Card>
      </GridItem>
      <GridItem lg={7}>
        <Card isFullHeight>
          <CardBody>
            <FormGroup label="Description" fieldId={descriptionId}>
              <TextArea
                id={descriptionId}
                readOnlyVariant={editing ? undefined : 'default'}
                rows={8}
                resizeOrientation="vertical"
                value={description}
                onChange={(_, value) => store.getState().setDescription(value)}
              />
            </FormGroup>
          </CardBody>
        </Card>
      </GridItem>
    </Grid>
  );
}
