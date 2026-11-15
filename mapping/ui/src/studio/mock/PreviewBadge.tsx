import { Label, Tooltip } from '@patternfly/react-core';
import { useRef } from 'react';

export interface PreviewBadgeProps {
  /** The preview feature, in the words of spec #152's table. */
  feature: string;
  /** Its follow-up issue under #162. */
  issue: number;
}

/**
 * The "Preview" badge of a preview feature (GLOSSARY, ADR-0028): its tooltip names the feature and its follow-up issue,
 * on hover and on keyboard focus.
 */
export function PreviewBadge({ feature, issue }: PreviewBadgeProps) {
  const badge = useRef<HTMLSpanElement>(null);
  return (
    <>
      <span ref={badge} className="preview-badge" tabIndex={0}>
        <Label isCompact color="purple">
          Preview
        </Label>
      </span>
      <Tooltip triggerRef={badge} content={`${feature}: a preview kept in this browser only, never in the mapping file (#${issue}).`} />
    </>
  );
}
