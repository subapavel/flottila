import type { StructureLoaded } from '../client';

const field = (step: string, min: number, max: number | undefined, valueType: string, nillable = false) => ({
  kind: step.startsWith('@') ? ('attribute' as const) : ('element' as const),
  step,
  namespace: '',
  localName: step.replace('@', ''),
  min,
  ...(max === undefined ? {} : { max }),
  valueType,
  nillable,
});

/**
 * The reply of `POST /api/structures` for `corpus/structures/skeleton/order.xsd`, root `Order`, as the real service
 * gave it on 2026-11-02 (the engine reads `xs:token` as `xs:string` and `xs:int` as `xs:integer`).
 */
export const orderStructure: StructureLoaded = {
  kind: 'loaded',
  fingerprint: 'sha256:df1714b8b2e4b09e326626a2ea72780bf6298a0ca733c69f5586ea657e4bd90d',
  root: {
    kind: 'element',
    step: 'Q{urn:flottila:corpus:skeleton}Order',
    namespace: 'urn:flottila:corpus:skeleton',
    localName: 'Order',
    min: 1,
    max: 1,
    nillable: false,
    type: '/Q{urn:flottila:corpus:skeleton}Order',
  },
  types: {
    '/Q{urn:flottila:corpus:skeleton}Order': [
      field('@channel', 0, 1, 'xs:string'),
      field('OrderId', 1, 1, 'xs:string'),
      field('Quantity', 0, 1, 'xs:integer'),
      field('Amount', 0, 1, 'xs:decimal', true),
      field('Urgent', 0, 1, 'xs:boolean'),
      field('DeliveryDate', 0, 1, 'xs:date'),
      field('PickupTime', 0, 1, 'xs:time'),
      field('CreatedAt', 0, 1, 'xs:dateTime'),
      field('Note', 0, undefined, 'xs:string'),
    ],
  },
};
