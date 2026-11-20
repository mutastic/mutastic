// mutastic's arid rules, as the engine and the parity contract give them: src/data/arid.json,
// written by `./gradlew :mutastic-cli:exportDocsOutput` and checked against the code by its tests.
// The pages about arid code take each rule's id, reasons and editions from here, so none of them
// is typed by hand and a rule added to the engine is on the page the day it ships.
import data from '../data/arid.json';

export type Edition = 'java' | 'rust';

export interface AridRule {
  id: string;
  anchor: string;
  url: string;
  editions: Edition[];
  scope: 'method' | 'site';
  description: string;
  reasons: Partial<Record<Edition, string[]>>;
  examples: Partial<Record<Edition, string>>;
}

export const rules: AridRule[] = (data as { rules: AridRule[] }).rules;

/** The rule with id `id`, `mutastic:waits`; the build stops on an id the engine doesn't have. */
export function rule(id: string): AridRule {
  const found = rules.find((each) => each.id === id);
  if (!found) {
    throw new Error(
      `${id} isn't one of mutastic's arid rules (src/data/arid.json has ${rules.map((r) => r.id).join(', ')})`,
    );
  }
  return found;
}

/** How a rule's scope reads on the page. */
export function scope(rule: AridRule): string {
  return rule.scope === 'method' ? 'a whole method' : 'one call or value';
}

/** A reason as a terminal shows it: the backticks around code are styling, not text. */
export function reasonText(reason: string): string {
  return reason.replaceAll('`', '');
}

/** A rule's name, as its section's heading says it: `mutastic:wait-times` → `Wait times`. */
export function title(rule: AridRule): string {
  const words = rule.anchor.replaceAll('-', ' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}
