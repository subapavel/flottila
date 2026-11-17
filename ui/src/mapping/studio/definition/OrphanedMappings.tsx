import { Button, CardBody, Content, DataList, DataListAction, DataListCell, DataListItem, DataListItemCells, DataListItemRow, Icon, Title } from '@patternfly/react-core';
import { ExclamationTriangleIcon, TrashIcon } from '@patternfly/react-icons';
import { useId } from 'react';
import type { FieldPath } from '../../generated/mapping-model';
import { localPath } from '../../model/field-path';

export interface OrphanedMappingsProps {
  /** The orphaned target field mappings' field paths, in the model's order. */
  paths: readonly FieldPath[];
  /** The selected target field: its entry is marked as open. */
  selected: FieldPath | null;
  editing: boolean;
  /** Shows the expression of a mapping. */
  onOpen: (path: FieldPath) => void;
  /** Deletes a mapping with all its boxes, one undo step. */
  onDelete: (path: FieldPath) => void;
}

/**
 * The orphaned target field mappings (those for a field the target structure lacks) in a group of their own below the
 * target tree, as they have no row in it: each with Open, which shows its expression, and Delete, enabled in edit mode
 * only.
 */
export function OrphanedMappings({ paths, selected, editing, onOpen, onDelete }: OrphanedMappingsProps) {
  const listId = useId();
  return (
    <CardBody isFilled={false} component="section" aria-label="Orphaned target field mappings">
      <Title headingLevel="h3" size="md">
        <Icon status="warning" isInline>
          <ExclamationTriangleIcon />
        </Icon>{' '}
        Orphaned target field mappings ({paths.length})
      </Title>
      <Content component="p">The target structure has no such fields. Open one to see its expression, or delete it in edit mode.</Content>
      <DataList isCompact aria-label="Orphaned target field mappings list">
        {paths.map((path) => {
          const shown = `/${localPath(path)}`;
          const open = selected === path;
          const itemId = `${listId}-${path}`;
          return (
            <DataListItem key={path} aria-label={shown}>
              <DataListItemRow>
                <DataListItemCells
                  dataListCells={[
                    <DataListCell key="path" id={`${itemId}-path`}>
                      <code title={path}>{shown}</code>
                    </DataListCell>,
                  ]}
                />
                <DataListAction aria-label={`Actions for ${shown}`} id={`${itemId}-actions`} aria-labelledby={`${itemId}-path`}>
                  <Button variant="link" isInline size="sm" aria-label={`Open ${shown}`} aria-pressed={open} isClicked={open} onClick={() => onOpen(path)}>
                    Open
                  </Button>
                  <Button variant="link" isInline isDanger size="sm" icon={<TrashIcon />} aria-label={`Delete ${shown}`} isDisabled={!editing} onClick={() => onDelete(path)}>
                    Delete
                  </Button>
                </DataListAction>
              </DataListItemRow>
            </DataListItem>
          );
        })}
      </DataList>
    </CardBody>
  );
}
