import { tmpdir } from 'node:os';
import { join } from 'node:path';

/** The temporary copy of corpus/ the @stack service runs over. The server command creates it, the scenario reads it. */
export const stackWorkspace = join(tmpdir(), 'flottila-stack-workspace');
