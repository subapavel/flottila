import { describe, expect, test } from 'vitest';
import { mappingServiceTarget } from './server';

describe('mappingServiceTarget', () => {
  test('is port 8080 when QUARKUS_HTTP_PORT is unset', () => {
    expect(mappingServiceTarget({})).toBe('http://127.0.0.1:8080');
  });

  test('follows QUARKUS_HTTP_PORT', () => {
    expect(mappingServiceTarget({ QUARKUS_HTTP_PORT: '8082' })).toBe('http://127.0.0.1:8082');
  });

  test('is port 8080 when QUARKUS_HTTP_PORT is empty', () => {
    expect(mappingServiceTarget({ QUARKUS_HTTP_PORT: '' })).toBe('http://127.0.0.1:8080');
  });
});
