import { describe, expect, test } from 'vitest';
import { messageName, messageNamespace, rootKind, type MessageChoice } from './structure-names';

const element: MessageChoice = { bundle: ['../structures/skeleton/order.xsd'], root: { element: 'Q{urn:flottila:corpus:skeleton}Order' } };
const bareElement: MessageChoice = { bundle: ['order.xsd'], root: { element: 'Order' } };
const emptyNamespace: MessageChoice = { bundle: ['order.xsd'], root: { element: 'Q{}Order' } };
const wsdl: MessageChoice = {
  bundle: ['service.wsdl'],
  root: { wsdl: { interface: 'Q{urn:example:orders}OrderPort', operation: 'PlaceOrder', direction: 'input' } },
};
const bareWsdl: MessageChoice = { bundle: ['service.wsdl'], root: { wsdl: { interface: 'OrderPort', operation: 'PlaceOrder', direction: 'output' } } };
const json: MessageChoice = { bundle: ['../schemas/invoice.schema.json', 'common.schema.json'], root: { json: '$' } };
const topJson: MessageChoice = { bundle: ['invoice.schema.json'], root: { json: '$' } };

describe('the names of a message on the Signature tab', () => {
  test('the kind of each root selector', () => {
    expect([element, wsdl, json].map((m) => rootKind(m.root))).toEqual(['element', 'wsdl', 'json']);
  });

  test('the name: an element local name, a WSDL operation, the last segment of a JSON root first file', () => {
    expect([element, bareElement, wsdl, json, topJson].map(messageName)).toEqual(['Order', 'Order', 'PlaceOrder', 'invoice.schema.json', 'invoice.schema.json']);
  });

  test('the namespace of an element or a WSDL interface; none for JSON or a name without one', () => {
    expect([element, wsdl].map(messageNamespace)).toEqual(['urn:flottila:corpus:skeleton', 'urn:example:orders']);
    expect([bareElement, emptyNamespace, bareWsdl, json].map(messageNamespace)).toEqual([undefined, undefined, undefined, undefined]);
  });
});
