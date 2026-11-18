import { describe, expect, test } from 'vitest';
import { EndpointFileError, readEndpoint, writeConfig } from './endpointYaml';

const partner = `# The partner of the demo.
schemaVersion: 1
kind: endpoint
id: partner-domestic
scheme: http
direction: out
config:
  url: http://localhost:8090/echo   # where it sends
  maxBodyBytes: 1048576
  # the partner is slow
  timeoutMs: 5000

environments:
  prod:
    config:
      url: https://partner.example/invoices
`;

describe('readEndpoint', () => {
  test('reads the envelope, the options and what each environment overrides', () => {
    expect(readEndpoint(partner)).toEqual({
      id: 'partner-domestic',
      scheme: 'http',
      direction: 'out',
      description: null,
      config: { url: 'http://localhost:8090/echo', maxBodyBytes: 1048576, timeoutMs: 5000 },
      environments: [{ name: 'prod', options: ['url'] }],
    });
  });

  test('a document of another kind is no endpoint', () => {
    expect(readEndpoint('schemaVersion: 1\nkind: flow\nid: f\n')).toBeNull();
    expect(readEndpoint('- a\n- b\n')).toBeNull();
  });

  test('an endpoint file without its envelope, or no YAML at all, is refused', () => {
    expect(() => readEndpoint('kind: endpoint\nid: a\nscheme: http\ndirection: sideways\nconfig: {}\n')).toThrow('the direction in or out');
    expect(() => readEndpoint('kind: endpoint\nid: a\nscheme: http\ndirection: in\n')).toThrow('no mapping under config');
    expect(() => readEndpoint('kind: endpoint\nid: [a\n')).toThrow(EndpointFileError);
  });
});

describe('writeConfig', () => {
  const config = { url: 'http://localhost:8090/echo', maxBodyBytes: 1048576, timeoutMs: 5000 };

  test('the same options leave every byte as it is', () => {
    expect(writeConfig(partner, config)).toBe(partner);
  });

  test('a changed value is rewritten on its own line and keeps the comment after it', () => {
    expect(writeConfig(partner, { ...config, url: 'http://localhost:8091/echo' })).toBe(
      partner.replace('url: http://localhost:8090/echo   # where it sends', 'url: http://localhost:8091/echo   # where it sends'),
    );
    expect(writeConfig(partner, { ...config, timeoutMs: 7000 })).toBe(partner.replace('timeoutMs: 5000', 'timeoutMs: 7000'));
  });

  test('a new option goes after the last one, before the comments and blank lines that follow the block', () => {
    expect(writeConfig(partner, { ...config, retries: 3 })).toBe(partner.replace('  timeoutMs: 5000\n', '  timeoutMs: 5000\n  retries: 3\n'));
  });

  test('an option left out is removed with its line, and the order of the others stays', () => {
    expect(writeConfig(partner, { url: config.url, timeoutMs: config.timeoutMs })).toBe(partner.replace('  maxBodyBytes: 1048576\n', ''));
    expect(writeConfig(partner, { timeoutMs: 5000, url: 'http://localhost:8090/echo', maxBodyBytes: 1048576 })).toBe(partner);
  });

  test('text that a reader could take for something else is written quoted', () => {
    const written = (value: unknown) => writeConfig('kind: endpoint\nconfig:\n  a: x\n', { a: value });
    expect(written('/inbox')).toBe('kind: endpoint\nconfig:\n  a: /inbox\n');
    expect(written('secret://sftp-password')).toBe('kind: endpoint\nconfig:\n  a: secret://sftp-password\n');
    expect(written('0.0.0.0')).toBe('kind: endpoint\nconfig:\n  a: "0.0.0.0"\n');
    expect(written('yes')).toBe('kind: endpoint\nconfig:\n  a: "yes"\n');
    expect(written('two words # not a comment')).toBe('kind: endpoint\nconfig:\n  a: "two words # not a comment"\n');
    expect(written('a:')).toBe('kind: endpoint\nconfig:\n  a: "a:"\n');
    expect(written(true)).toBe('kind: endpoint\nconfig:\n  a: true\n');
    expect(written({ deep: [1, 'two'] })).toBe('kind: endpoint\nconfig:\n  a: {"deep":[1,"two"]}\n');
  });

  test('a value over several lines is replaced whole, and a quoted value keeps its comment', () => {
    const text = 'kind: endpoint\nconfig:\n  headers:\n    a: 1\n    b: 2\n  path: "/in" # the door\nid: x\n';
    expect(writeConfig(text, { headers: { a: 1 }, path: '/out' })).toBe('kind: endpoint\nconfig:\n  headers: {"a":1}\n  path: /out # the door\nid: x\n');
  });

  test('an empty config is written as {}, and options are added to one', () => {
    const text = 'kind: endpoint\nconfig: {}   # nothing yet\nid: x\n';
    expect(writeConfig(text, { port: 8080 })).toBe('kind: endpoint\nconfig:   # nothing yet\n  port: 8080\nid: x\n');
    expect(writeConfig('kind: endpoint\nconfig:\n  port: 8080\n', {})).toBe('kind: endpoint\nconfig: {}\n');
  });

  test('Windows line ends stay', () => {
    const text = 'kind: endpoint\r\nconfig:\r\n  port: 8080\r\n';
    expect(writeConfig(text, { port: 8081 })).toBe('kind: endpoint\r\nconfig:\r\n  port: 8081\r\n');
  });

  test('a config the form cannot edit line by line is refused, and nothing is written', () => {
    expect(() => writeConfig('kind: endpoint\nconfig: {port: 8080}\n', { port: 8081 })).toThrow('edit the file in a text editor');
    expect(() => writeConfig('kind: endpoint\nconfig:\n  ports:\n  - 1\n', { ports: [2] })).toThrow('edit the file in a text editor');
    expect(() => writeConfig('kind: endpoint\nconfig: &c\n  port: 8080\nother: *c\n', { port: 8081 })).toThrow(EndpointFileError);
  });
});
