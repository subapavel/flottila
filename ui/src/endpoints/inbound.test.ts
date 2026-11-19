import { describe, expect, test } from 'vitest';
import { inboundAddress, optionsIn } from './inbound';

describe('optionsIn', () => {
  const environments = [
    { name: 'prod', options: ['address', 'tls'], config: { address: '/orders', tls: { on: true } } },
    { name: 'test', options: [], config: {} },
  ];

  test("lays the environment's overlay over the options, each key it names replaced whole", () => {
    expect(optionsIn({ address: '/in', tls: { on: false, ca: 'x' }, flow: 'a' }, environments, 'prod')).toEqual({ address: '/orders', tls: { on: true }, flow: 'a' });
  });

  test('an environment the file does not name, or one that overrides nothing, leaves the options as they are', () => {
    expect(optionsIn({ address: '/in' }, environments, 'dev')).toEqual({ address: '/in' });
    expect(optionsIn({ address: '/in' }, environments, 'test')).toEqual({ address: '/in' });
  });
});

describe('inboundAddress', () => {
  test("is the prefix and the address after the node's origin", () => {
    expect(inboundAddress('/http', '/in', 'http://localhost:8080')).toBe('http://localhost:8080/http/in');
  });

  test('is the path alone with no origin known', () => {
    expect(inboundAddress('/http', '/invoice/async', null)).toBe('/http/invoice/async');
  });

  test('is null for an address that is no path', () => {
    for (const address of [undefined, null, '', 'in', 42, { path: '/in' }]) expect(inboundAddress('/http', address, null)).toBeNull();
  });
});
