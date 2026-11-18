import { Button, Card, CardBody, CardTitle, CodeBlock, CodeBlockAction, CodeBlockCode, Content, Modal, ModalBody, ModalFooter, ModalHeader, Title } from '@patternfly/react-core';
import { useId } from 'react';
import { CopyTextButton } from './CopyTextButton';

/** The processing log of a tab's last action, with a copy button. */
export function ProcessingLog({ log }: { log: string }) {
  return (
    <Card>
      <CardTitle>
        <Title headingLevel="h3" size="md">
          Processing Log
        </Title>
      </CardTitle>
      <CardBody>
        <CodeBlock
          actions={
            <CodeBlockAction>
              <CopyTextButton label="processing log" text={log} />
            </CodeBlockAction>
          }
        >
          <CodeBlockCode>{log}</CodeBlockCode>
        </CodeBlock>
      </CardBody>
    </Card>
  );
}

/** The Processing Log dialog an action opens with its log. */
export function LogDialog({ log, onClose }: { log: string; onClose: () => void }) {
  const title = useId();
  return (
    <Modal variant="small" isOpen onClose={onClose} aria-labelledby={title}>
      <ModalHeader title="Processing Log" labelId={title} />
      <ModalBody>
        <Content component="p">{log}</Content>
      </ModalBody>
      <ModalFooter>
        <Button onClick={onClose}>Close</Button>
      </ModalFooter>
    </Modal>
  );
}
