import { Button, Tooltip } from '@patternfly/react-core';
import { CheckIcon, CopyIcon, ExclamationCircleIcon } from '@patternfly/react-icons';
import { useEffect, useRef, useState, type ReactNode } from 'react';

const COPIED = 'Copied!';
/** How long the receipt shows, as in the prototype's CopyTextButton. */
const RECEIPT_MS = 2400;

/**
 * Copies `text` to the clipboard (the prototype's CopyTextButton): its icon, its tooltip and a screen-reader status
 * say whether it did, for 2.4 s. The tooltip shows while the button is hovered or focused, or a receipt shows; Escape
 * hides it and the receipt. The prototype's `execCommand('copy')` fallback is left out: the browsers the studio
 * supports all have the asynchronous clipboard.
 */
export function CopyTextButton({ label, text }: { label: string; text: string }) {
  const trigger = useRef<HTMLButtonElement>(null);
  const [hover, setHover] = useState(false);
  const [focus, setFocus] = useState(false);
  const [receipt, setReceipt] = useState<string | null>(null);
  useEffect(() => {
    if (receipt === null) return undefined;
    const timer = setTimeout(() => setReceipt(null), RECEIPT_MS);
    return () => clearTimeout(timer);
  }, [receipt]);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setReceipt(COPIED);
    } catch {
      setReceipt('Could not copy. Select the text and copy it manually.');
    }
  };
  const icon = receipt === null ? <CopyIcon /> : receipt === COPIED ? <CheckIcon /> : <ExclamationCircleIcon />;
  return (
    <>
      <Button
        ref={trigger}
        variant="plain"
        className="copy-text-button"
        aria-label={`Copy ${label} to clipboard`}
        onClick={() => void copy()}
        icon={icon}
        onMouseEnter={() => setHover(true)}
        onMouseLeave={() => setHover(false)}
        onFocus={() => setFocus(true)}
        onBlur={() => setFocus(false)}
        onKeyDown={(event) => {
          if (event.key !== 'Escape') return;
          setFocus(false);
          setHover(false);
          setReceipt(null);
        }}
      />
      {(hover || focus || receipt !== null) && (
        <Tooltip
          className="copy-tooltip"
          aria="none"
          aria-live="off"
          triggerRef={trigger}
          trigger="manual"
          isVisible
          position="top-end"
          maxWidth="16rem"
          content={receipt ?? 'Copy to clipboard'}
        />
      )}
      <span className="pf-v6-screen-reader" role="status">
        {receipt}
      </span>
    </>
  );
}

/**
 * A surface with its copy button in the top end corner (the prototype's CopySurface): a document, a log or a path, whose
 * text `text` the button copies.
 */
export function CopySurface({ label, text, className, children }: { label: string; text: string; className: string; children: ReactNode }) {
  return (
    <div className={`copy-surface ${className}`}>
      {children}
      <div className="clipboard-actions">
        <CopyTextButton label={label} text={text} />
      </div>
    </div>
  );
}
