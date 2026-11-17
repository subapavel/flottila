import { Label, Tooltip } from '@patternfly/react-core';
import { useRef } from 'react';

export interface PreviewBadgeProps {
  /** The preview feature's name. */
  feature: string;
  /** Its follow-up issue. */
  issue: number;
}

/**
 * The "Preview" badge of a preview feature, one the studio shows but does not store in the mapping: its tooltip names
 * the feature and its follow-up issue, on hover and on keyboard focus.
 */
export function PreviewBadge({ feature, issue }: PreviewBadgeProps) {
  const badge = useRef<HTMLSpanElement>(null);
  return (
    <>
      <span ref={badge} tabIndex={0}>
        <Label isCompact color="purple">
          Preview
        </Label>
      </span>
      <Tooltip triggerRef={badge} content={`${feature}: a preview kept in this browser only, never in the mapping file (#${issue}).`} />
    </>
  );
}
