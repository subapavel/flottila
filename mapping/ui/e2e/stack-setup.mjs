// Prepares the @stack workspace copy of corpus/, given as the one argument, so that no two scenarios edit one file:
// - mappings/order-id-copy, e2e-edit and e2e-attribute: order-id-copy without its target field mappings, so the
//   scenarios' drags are real;
// - mappings/e2e-refresh: order-id-copy as it is, its source read from a copy of the skeleton schema of its own,
//   structures/e2e-refresh/order.xsd, which the refresh scenario changes.
import { copyFileSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const workspace = process.argv[2];
const mappings = join(workspace, 'mappings');
const write = (name, model) => writeFileSync(join(mappings, `${name}.mapping.json`), JSON.stringify(model, null, 2) + '\n');
const linked = JSON.parse(readFileSync(join(mappings, 'order-id-copy.mapping.json'), 'utf8'));

mkdirSync(join(workspace, 'structures', 'e2e-refresh'));
copyFileSync(join(workspace, 'structures', 'skeleton', 'order.xsd'), join(workspace, 'structures', 'e2e-refresh', 'order.xsd'));
write('e2e-refresh', { ...linked, source: { ...linked.source, bundle: ['../structures/e2e-refresh/order.xsd'] } });

const unlinked = { ...linked };
delete unlinked.targetFieldMappings;
for (const name of ['order-id-copy', 'e2e-edit', 'e2e-attribute']) write(name, unlinked);
