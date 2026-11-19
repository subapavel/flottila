import { tmpdir } from 'node:os';
import { join } from 'node:path';

/**
 * The temporary copy of the mapping corpus the @stack development server uses as its working folder. The server command
 * creates it, the scenarios read it.
 */
export const stackWorkspace = join(tmpdir(), 'rail8-stack-workspace');
