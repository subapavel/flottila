import { expect, test } from 'vitest';

test('browser mode runs in a real Chromium', () => {
  expect(navigator.userAgent).toContain('Chrome');
});
