import { ESLint } from 'eslint';
import { fileURLToPath } from 'node:url';
import { describe, expect, test } from 'vitest';

// The import boundaries of ADR-0013 ("Modules of ui/src"), with the studio of ADR-0028 in place of the editor, checked
// against the real eslint.config.js.
const eslint = new ESLint({ cwd: fileURLToPath(new URL('..', import.meta.url)) });

async function restricted(file: string, specifier: string): Promise<boolean> {
  const [result] = await eslint.lintText(`import x from '${specifier}';\nexport default x;\n`, { filePath: file });
  return result!.messages.some((m) => m.ruleId === 'no-restricted-imports');
}

describe('import boundaries (ADR-0013, ADR-0028)', () => {
  test.each([
    ['src/model/link.ts', '../generated/mapping-model'],
    ['src/model/link.ts', 'immer'],
    ['src/model/link.ts', './same-model'],
    ['src/api/http-api.ts', '../generated/api'],
    ['src/api/http-api.ts', './client'],
    ['src/api/fake/fake-api.ts', '../client'],
    ['src/api/fake/fake-api.ts', '../../generated/api'],
    ['src/api/fake/fake-api.ts', '../../../../corpus/mappings/order-id-copy.mapping.json?raw'],
    ['src/api/fake/fake-api.ts', '../../../../contract/function-catalogue.json?raw'],
    ['src/studio/store/working-copy.ts', '../../model/link'],
    ['src/studio/store/working-copy.ts', '../../api/client'],
    ['src/studio/store/working-copy.ts', '../../generated/mapping-model'],
    ['src/studio/store/working-copy.ts', 'zustand/vanilla'],
    ['src/studio/store/working-copy.ts', '../browser-state/browser-state'],
    ['src/studio/browser-state/browser-state.ts', '../../generated/mapping-model'],
    ['src/studio/shell/StudioShell.tsx', '../store/working-copy'],
    ['src/studio/shell/StudioShell.tsx', '../definition/DefinitionTab'],
    ['src/studio/shell/StudioShell.tsx', '@patternfly/react-core'],
    ['src/studio/shell/SaveConflict.tsx', '../../api/client'],
    ['src/studio/definition/expression/QueueDialog.tsx', './display-queue'],
    ['src/studio/definition/SchemaPane.tsx', '@tanstack/react-virtual'],
    ['src/studio/definition/SchemaPane.tsx', './rows'],
    ['src/studio/definition/DefinitionTab.tsx', './expression/Canvas'],
    ['src/studio/definition/expression/Canvas.tsx', '@xyflow/react'],
    ['src/studio/definition/expression/expression-layout.ts', '@dagrejs/dagre'],
    ['src/studio/definition/expression/Canvas.tsx', '../../store/working-copy'],
    ['src/app/main.tsx', '../studio/browser-state/browser-state'],
    ['src/app/main.tsx', '../api/fake/fake-api'],
    ['src/app/Page.tsx', '../studio/shell/Studio'],
    // tabs meet through the store; shared code is in shared/ and mock/ (ADR-0013, ADR-0028)
    ['src/studio/test/TestTab.tsx', '../shared/SplitLayout'],
    ['src/studio/test/TestTab.tsx', '../mock/test-instances'],
    ['src/studio/test/TestTab.tsx', '../browser-state/browser-state'],
    ['src/studio/signature/Signature.tsx', '../shared/structure-query'],
    ['src/studio/overview/MappingOverview.tsx', '../shared/structure-names'],
    ['src/studio/functions/FunctionsTab.tsx', '../mock/function-model'],
    ['src/studio/definition/expression/Canvas.tsx', '../../shared/FieldTypeIcon'],
    ['src/studio/definition/expression/Canvas.tsx', '../../mock/preview-functions'],
    ['src/studio/definition/expression/Canvas.tsx', '../rows'],
    ['src/studio/definition/expression/Canvas.tsx', './QueueDialog'],
    ['src/studio/shell/StudioShell.tsx', '../test/TestTab'],
    ['src/studio/shell/StudioShell.tsx', '../shared/CopyTextButton'],
    ['src/studio/mock/InstanceTools.tsx', '../shared/Tool'],
    ['src/studio/shared/SplitLayout.tsx', '../browser-state/browser-state'],
    ['src/studio/shared/SplitLayout.tsx', './Tool'],
  ])('%s may import %s', async (file, specifier) => {
    expect(await restricted(file, specifier)).toBe(false);
  });

  test.each([
    ['src/model/link.ts', 'react'],
    ['src/model/link.ts', 'zustand'],
    ['src/model/link.ts', '../api/client'],
    ['src/model/link.ts', '../studio/store/working-copy'],
    ['src/api/http-api.ts', '../model/link'],
    ['src/api/http-api.ts', '../studio/store/working-copy'],
    ['src/api/http-api.ts', 'react'],
    ['src/api/fake/fake-api.ts', '../../model/link'],
    ['src/api/fake/fake-api.ts', '../../../../service/pom.xml?raw'],
    ['src/model/link.ts', '@tanstack/react-virtual'],
    ['src/api/fake/fake-api.ts', '@tanstack/react-virtual'],
    ['src/studio/shell/StudioShell.tsx', '../../app/main'],
    ['src/studio/shell/StudioShell.tsx', '../../app/routes'],
    ['src/studio/definition/expression/Canvas.tsx', '../../../app/Page'],
    ['src/studio/shell/StudioShell.tsx', '@xyflow/react'],
    ['src/studio/shell/StudioShell.tsx', '@dagrejs/dagre'],
    ['src/studio/shell/StudioShell.tsx', '@tanstack/react-virtual'],
    ['src/studio/test/TestTab.tsx', '@xyflow/react'],
    ['src/studio/test/TestTab.tsx', '@tanstack/react-virtual'],
    ['src/studio/store/working-copy.ts', '../shell/StudioShell'],
    ['src/studio/store/working-copy.ts', '../definition/rows'],
    ['src/studio/store/working-copy.ts', '../../app/main'],
    ['src/studio/store/working-copy.ts', 'react'],
    ['src/studio/store/working-copy.ts', 'react-dom/client'],
    ['src/studio/store/working-copy.ts', '@patternfly/react-core'],
    ['src/studio/store/working-copy.ts', '@xyflow/react'],
    ['src/studio/store/working-copy.ts', '@dagrejs/dagre'],
    ['src/studio/store/working-copy.ts', '@tanstack/react-virtual'],
    ['src/studio/browser-state/browser-state.ts', '../mock/preview-draft'],
    ['src/studio/browser-state/browser-state.ts', '@dagrejs/dagre'],
    ['src/studio/definition/SchemaPane.tsx', '../../app/main'],
    ['src/studio/definition/SchemaPane.tsx', '@xyflow/react'],
    ['src/studio/definition/SchemaPane.tsx', '@dagrejs/dagre'],
    ['src/studio/definition/expression/Canvas.tsx', '@tanstack/react-virtual'],
    ['src/app/main.tsx', '@xyflow/react'],
    ['src/app/main.tsx', '@dagrejs/dagre'],
    ['src/app/main.tsx', '@tanstack/react-virtual'],
    // a tab imports no other tab, nor the shell that holds them (ADR-0013: subfolders meet through the store)
    ['src/studio/test/TestTab.tsx', '../definition/SplitLayout'],
    ['src/studio/test/TestTab.tsx', '../definition/expression/expression-graph'],
    ['src/studio/test/TestDocument.tsx', '../functions/download'],
    ['src/studio/test/TestTab.tsx', '../shell/CopyTextButton'],
    ['src/studio/definition/DefinitionTab.tsx', '../test/test-session'],
    ['src/studio/definition/expression/Canvas.tsx', '../../test/QueueDialog'],
    ['src/studio/definition/expression/DefinitionTools.tsx', '../../test/InstanceTools'],
    ['src/studio/definition/expression/Canvas.tsx', '../../shell/StudioShell'],
    ['src/studio/overview/MappingOverview.tsx', '../signature/structure-names'],
    ['src/studio/signature/Signature.tsx', '../shell/CopyTextButton'],
    ['src/studio/signature/Signature.tsx', '../definition/structure-query'],
    ['src/studio/functions/FunctionsTab.tsx', '../test/documents'],
    // shared code imports no tab, so that no tab reaches another through it
    ['src/studio/mock/functions-store.ts', '../functions/function-model'],
    ['src/studio/mock/signature-mock.ts', '../signature/structure-names'],
    ['src/studio/mock/InstanceTools.tsx', '../shell/StudioShell'],
    ['src/studio/shared/SplitLayout.tsx', '../definition/rows'],
    ['src/studio/shared/SplitLayout.tsx', '../test/TestTab'],
    ['src/studio/shared/SplitLayout.tsx', '@xyflow/react'],
  ])('%s may not import %s', async (file, specifier) => {
    expect(await restricted(file, specifier)).toBe(true);
  });
});
