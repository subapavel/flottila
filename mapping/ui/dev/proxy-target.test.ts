import { describe, expect, test } from 'vitest';
import { apiTarget } from './proxy-target';

// The Vite dev proxy follows the port Quarkus listens on (QUARKUS_HTTP_PORT), 8080 otherwise.
describe('apiTarget', () => {
  test('defaults to 8080 when the variable is unset', () => {
    expect(apiTarget({})).toBe('http://127.0.0.1:8080');
  });

  test('follows QUARKUS_HTTP_PORT', () => {
    expect(apiTarget({ QUARKUS_HTTP_PORT: '8081' })).toBe('http://127.0.0.1:8081');
  });

  test('defaults to 8080 when the variable is an empty string', () => {
    expect(apiTarget({ QUARKUS_HTTP_PORT: '' })).toBe('http://127.0.0.1:8080');
  });
});
