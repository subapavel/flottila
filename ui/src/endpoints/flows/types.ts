import type { FlowContext as ServedContext } from '../files/flowFiles';
import type { NodeIngress } from '../inbound';
import type { SettingsDescription, SettingsField } from '../settings';

/** Where a value stands in the file: `['steps', 2, 'xpath']`. */
export type Path = readonly (string | number)[];

/** The step types the node serves from its modules and plugins, or why they could not be read. */
export type ServedSteps = { type: string; configSchema: unknown }[] | { missing: string };

/**
 * What a flow refers to, from the development server (`endpoints`, `flows`, `partial`) and the node (`steps`, `ingress`).
 * The endpoints are those of the flow's package; `address` is an endpoint's own, `overlaid` says an environment changes
 * it, and `addressIn` what each such environment sets it to. The flows are those of the working folder, `address` the one
 * their inbound `direct` endpoint serves. `partial` says the working folder was not read whole, so that what is not found
 * may still exist. `ingress`, when the node could be asked, is where the node takes requests.
 */
export type FlowContext = Omit<ServedContext, 'partial'> & { partial?: boolean; steps: ServedSteps; ingress?: FlowIngress };

/** Where the node takes requests: its environment, its advertised origin, and the HTTP prefix of each scheme that has one. */
export interface FlowIngress extends NodeIngress {
  prefixes: Record<string, string>;
}

/**
 * A field of a step's form: an endpoint's kinds of field, and lists and maps, shown as tables. `emptyAllowed` takes empty
 * text as a value; `reference` marks a field that names an endpoint of the package or a process of the flow.
 */
export type StepField =
  | ValueField
  | (Omit<SettingsField, 'type'> & { type: 'list'; item: StepField[] })
  | (Omit<SettingsField, 'type'> & { type: 'map'; key: { pattern?: string }; value: StepField });

/** A field that holds one value: an endpoint's kinds of field, or `raw` for a value of any shape. */
export type ValueField = Omit<SettingsField, 'type'> & {
  type: 'text' | 'secret' | 'integer' | 'boolean' | 'choice' | 'raw';
  emptyAllowed?: boolean;
  reference?: 'endpoint' | 'process';
};

/** A step's settings description: an endpoint's, whose fields may also be lists and maps. */
export interface StepSettings extends Omit<SettingsDescription, 'fields'> {
  fields: StepField[];
}

/** The form of one step: where it stands, its description, and its keys as the file holds them. */
export interface StepForm {
  path: Path;
  settings: StepSettings;
  values: Record<string, unknown>;
  /** Why the step has no description of its own, and is shown in a raw editor. */
  missing?: string;
}

/** One finding in the flow: where it is and what is wrong; a warning is something that may still be right. */
export interface FlowProblem {
  path: Path;
  message: string;
  warning?: true;
}

/** A node of the picture. Its id is its path as a JSON Pointer, with a suffix for a node drawn beside that path. */
export interface FlowNode {
  id: string;
  kind: 'from' | 'step' | 'destination' | 'flow' | 'end' | 'lane' | 'group';
  path: Path;
  /** At most 8 of the names its runs have in a message's history: 'validate'; 'enrich/map'. */
  runsAs: string[];
  /** How many names it runs under. */
  runs: number;
  type?: string;
  /** The step's name, else its default name. */
  label: string;
  /** True when label is the default name: shown dimmed, never written. */
  defaultName: boolean;
  detail?: string;
  /** A route's rules, at most 8. */
  rows?: string[];
  /** A file of the working folder the node links to. */
  opens?: { flow: string } | { endpoint: string };
  x: number;
  y: number;
  width: number;
  height: number;
  /** The lane or frame the node is drawn in. */
  parent?: string;
  form?: StepForm;
  /** Why an edit would be refused on this node. */
  locked?: string;
}

/** An edge of the picture; its kind tells what a message does there. */
export interface FlowEdge {
  id: string;
  source: string;
  target: string;
  kind: 'next' | 'choice' | 'join' | 'ends' | 'each' | 'process' | 'calls';
  label?: string;
  /** Where a step dropped on this edge is added. */
  insertAt?: Path;
}

/** What is drawn of a flow file. When `readable` is false the file cannot be drawn and its text is shown with the problems. */
export interface FlowView {
  readable: boolean;
  nodes: FlowNode[];
  edges: FlowEdge[];
  problems: FlowProblem[];
  /** The form of the flow's own settings, at the path [], when the flow is drawn. */
  settings?: StepForm;
  /** Why the editor shows the flow read-only, when it does: a schemaVersion other than 1, or a list of steps on one line. */
  readOnly?: string;
}
