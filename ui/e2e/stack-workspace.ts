import { tmpdir } from 'node:os';
import { join } from 'node:path';

/**
 * The temporary copy of the mapping corpus the @stack service runs over. The server command creates it, the scenario
 * reads it.
 */
export const stackWorkspace = join(tmpdir(), 'rail8-stack-workspace');
