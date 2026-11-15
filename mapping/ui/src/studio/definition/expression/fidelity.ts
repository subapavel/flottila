import type { Fidelity } from '../../../generated/function-catalogue';

/**
 * What the studio says of a function's fidelity (GLOSSARY: Fidelity, ADR-0026): a deviation's catalogue text, as a neutral
 * note; nothing for an exact function or an assumed reading, whose level stays internal (#207).
 */
export function deviationNote(fidelity: Fidelity): string | undefined {
  return fidelity.level === 'deviation' ? fidelity.text : undefined;
}
