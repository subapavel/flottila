import { describe, expect, test } from 'vitest';
import { listHref, mappingOf, studioHref } from './routes';

describe('the page addresses (ADR-0028: ?mapping=<id> and the tab as the hash)', () => {
  test('the mapping is the mapping parameter, or none on the start page', () => {
    expect(mappingOf('?mapping=mappings/order-id-copy&api=fake')).toBe('mappings/order-id-copy');
    expect(mappingOf('?api=fake')).toBeNull();
    expect(mappingOf('')).toBeNull();
  });

  test("a studio address sets the mapping and the tab and keeps the page's other parameters but the field", () => {
    expect(studioHref('?api=fake', 'orders/in/order', 'definition')).toBe('?api=fake&mapping=orders%2Fin%2Forder#definition');
    expect(studioHref('?mapping=old&field=%2FA&api=fake', 'new', 'signature')).toBe('?mapping=new&api=fake#signature');
    expect(studioHref('', 'a b', 'overview')).toBe('?mapping=a+b#overview');
  });

  test("the start page's address drops the mapping and the field and keeps the other parameters", () => {
    expect(listHref('?mapping=x&field=%2FA&api=fake')).toBe('?api=fake');
    expect(listHref('?mapping=x')).toBe('?');
  });
});
