import { Modal, ModalBody, ModalHeader } from '@patternfly/react-core';
import { LayerGroupIcon, QuestionCircleIcon } from '@patternfly/react-icons';
import { useState } from 'react';
import './shell.css';

const ABOUT = 'About Mapping studio';

/**
 * The studio's masthead, "Flottila | Mapping studio" (ADR-0028): the brand links to the start page at `homeHref`; on the
 * right, the help button opens About Mapping studio, the prototype's help adapted to our product.
 */
export function Masthead({ homeHref }: { homeHref: string }) {
  const [about, setAbout] = useState(false);
  return (
    <header className="masthead">
      <a className="brand" href={homeHref}>
        <span className="brand-icon">
          <LayerGroupIcon />
        </span>
        Flottila
      </a>
      <span className="mast-divider" />
      <span className="mast-product">Mapping studio</span>
      <div className="mast-right">
        <button className="help-button" aria-label={ABOUT} title={ABOUT} onClick={() => setAbout(true)}>
          <QuestionCircleIcon />
        </button>
      </div>
      <Modal variant="small" isOpen={about} onClose={() => setAbout(false)} aria-label={ABOUT}>
        <ModalHeader title={ABOUT} />
        <ModalBody>
          <p>Open a mapping from its Flottila package. Choose Edit to make changes.</p>
          <p>
            Drag a source field onto a target for a direct connection. In its expression, drag from an output to an input to connect them.
            Dropping on an occupied input replaces its wire. Move either end of a wire to reconnect it, or right-click it and choose
            Disconnect. To connect with the keyboard, focus a node, press Shift+F10 and choose Connect to.
          </p>
          <p>
            Drag the canvas to pan. Lay Out Mapping arranges the expression from left to right at its fixed scale. Copying a function also
            copies its input branches. Paste adds a separate copy, with no connection to the target. Delete Floating Objects removes all
            objects and branches that have no path to the target. A check mark means complete, an exclamation mark indicates a missing input
            or an incomplete dependency, and a dash means there is no path to the target.
          </p>
          <p>
            Double-click a function with parameters or use its gear to edit them. Right-click a node and choose Display Queue to show its
            queue in the last test run. Save writes the mapping file to the workspace. Test runs the mapping on the service.
          </p>
        </ModalBody>
      </Modal>
    </header>
  );
}
