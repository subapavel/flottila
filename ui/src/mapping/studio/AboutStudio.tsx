import { Button, Modal, ModalBody, ModalHeader } from '@patternfly/react-core';
import { QuestionCircleIcon } from '@patternfly/react-icons';
import { useState } from 'react';

const ABOUT = 'About Mapping studio';

/** The studio's help button, which opens About Mapping studio: how to edit, connect, lay out, save and test a mapping. */
export function AboutStudio() {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button variant="plain" aria-label={ABOUT} title={ABOUT} icon={<QuestionCircleIcon />} onClick={() => setOpen(true)} />
      <Modal variant="small" isOpen={open} onClose={() => setOpen(false)} aria-label={ABOUT}>
        <ModalHeader title={ABOUT} />
        <ModalBody>
          <p>Open a mapping from its Rail8 package. Choose Edit to make changes.</p>
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
    </>
  );
}
