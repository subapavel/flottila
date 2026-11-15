import type { StudioTab } from '../studio/shell/StudioShell';

/**
 * The page's addresses (ADR-0028): the start page lists the mappings, `?mapping=<id>` opens one in the studio, and the
 * hash names its tab. Other parameters, such as `api=fake`, stay; `field`, which the old editor's addresses carried, is
 * dropped, since no studio page reads it.
 */
export function mappingOf(search: string): string | null {
  return new URLSearchParams(search).get('mapping');
}

/** The address of mapping `id` in the studio on `tab`, relative to the page whose query is `search`. */
export function studioHref(search: string, id: string, tab: StudioTab): string {
  const params = new URLSearchParams(search);
  params.delete('field');
  params.set('mapping', id);
  return `?${params}#${tab}`;
}

/** The address of the start page, relative to the page whose query is `search`. */
export function listHref(search: string): string {
  const params = new URLSearchParams(search);
  params.delete('mapping');
  params.delete('field');
  return `?${params}`;
}
