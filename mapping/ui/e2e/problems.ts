import type { Page } from '@playwright/test';

/** Collects every `console.error` and `pageerror` of the page (ADR-0013): a scenario ends with the list empty. */
export function collectProblems(page: Page): string[] {
  const problems: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') problems.push(message.text());
  });
  page.on('pageerror', (error) => problems.push(error.message));
  return problems;
}
