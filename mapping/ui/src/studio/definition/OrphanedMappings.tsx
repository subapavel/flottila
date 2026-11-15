import { Button } from '@patternfly/react-core';
import { ExclamationTriangleIcon, TrashIcon } from '@patternfly/react-icons';
import type { FieldPath } from '../../generated/mapping-model';
import { localPath } from '../../model/field-path';

export interface OrphanedMappingsProps {
  /** The orphaned target field mappings' field paths, in the model's order. */
  paths: readonly FieldPath[];
  /** The selected target field: its entry is marked as open. */
  selected: FieldPath | null;
  editing: boolean;
  /** Shows the expression of a mapping on the canvas. */
  onOpen: (path: FieldPath) => void;
  /** Deletes a mapping with all its boxes, one undo step. */
  onDelete: (path: FieldPath) => void;
}

/**
 * The orphaned target field mappings (GLOSSARY, story 47) in a group of their own below the target tree, as they have
 * no row in it: each with Open, which shows its expression on the canvas, and Delete, enabled in edit mode only.
 */
export function OrphanedMappings({ paths, selected, editing, onOpen, onDelete }: OrphanedMappingsProps) {
  return (
    <section className="orphaned-mappings" aria-label="Orphaned target field mappings">
      <h3>
        <span className="orphaned-mark" aria-hidden="true">
          <ExclamationTriangleIcon />
        </span>
        Orphaned target field mappings ({paths.length})
      </h3>
      <p className="orphaned-mappings-help">The target structure has no such fields. Open one to see its expression, or delete it in edit mode.</p>
      <ul>
        {paths.map((path) => {
          const shown = `/${localPath(path)}`;
          const open = selected === path;
          return (
            <li key={path} className={open ? 'active-field' : undefined}>
              <code title={path}>{shown}</code>
              <Button variant="link" isInline size="sm" aria-label={`Open ${shown}`} aria-pressed={open} onClick={() => onOpen(path)}>
                Open
              </Button>
              <Button variant="link" isInline isDanger size="sm" icon={<TrashIcon />} aria-label={`Delete ${shown}`} isDisabled={!editing} onClick={() => onDelete(path)}>
                Delete
              </Button>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
