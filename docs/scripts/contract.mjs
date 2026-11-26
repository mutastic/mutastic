// The editions' contract's own tooling, one implementation that every edition calls: the Java
// edition from here, any other through its scripts, which fetch this file from mutastic. The
// contract is public/contract/v1.json, its JSON Schema public/schemas/contract/v1.json; this
// checks what the schema can't say, and holds the pin logic and the release rule.
//
//   node docs/scripts/contract.mjs check [--contract FILE]
//       Every edition a difference names has a binding; every slot (a difference with `each`) has
//       each binding's `says`; each binding's arid rules, error names and selector name what the
//       contract has; a pending entry has an issue and a target.
//   node docs/scripts/contract.mjs release <edition> <X.Y.Z> [--contract FILE]
//       No edition releases with a pending entry it hasn't registered: one with no issue or
//       target, or whose target is a release that has gone by. A dated entry fails at its `until`.
//   node docs/scripts/contract.mjs pin <slug> [<commit or branch>]     (in an edition's repository)
//   node docs/scripts/contract.mjs prune                               (at a release)
//   node docs/scripts/contract.mjs newest                              (the newest pin's commit)
//       An edition that pins the contract keeps one file to a pull request in contract/pins, each
//       naming a commit of mutastic, its committer date and its position on main, and reads the
//       newest: contract/source.json names the repository, the file and the anchor positions
//       count from. mutastic-rs's contract/README.md says why.
//
// Node with no dependencies, so an edition needs only node on its path.
import { existsSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = fileURLToPath(new URL('../public/contract/v1.json', import.meta.url));

/** The contract at `file`, this repository's own when none is named. */
export function load(file = HERE) {
  return JSON.parse(readFileSync(file, 'utf8'));
}

/** The editions, by name, in the order the contract lists their bindings. */
export function editions(contract) {
  return Object.keys(contract.editions ?? {});
}

/** The differences that name `edition`: the slots, which every edition fills, and its deviations. */
export function differencesOf(contract, edition) {
  return contract.differences.filter((d) => d.each === true || Object.hasOwn(d.editions ?? {}, edition));
}

/** What's wrong with the contract that its schema can't say, each as a sentence. */
export function problems(contract) {
  const names = editions(contract);
  const found = [];
  if (!names.includes(contract.reference)) found.push(`the reference '${contract.reference}' has no binding in editions`);
  const ids = contract.differences.map((d) => d.id);
  for (const id of new Set(ids.filter((id, at) => ids.indexOf(id) !== at))) found.push(`the difference id ${id} is used twice`);
  const slots = contract.differences.filter((d) => d.each === true).map((d) => d.id);
  for (const d of contract.differences) {
    for (const [name, deviation] of Object.entries(d.editions ?? {})) {
      if (!names.includes(name)) found.push(`${d.id} names '${name}', which has no binding in editions`);
      if (deviation.kind === 'pending' && !(deviation.issue && deviation.target)) {
        found.push(`${d.id} is pending for ${name} without ${deviation.issue ? 'a target' : 'an issue'}: give it both`);
      }
    }
  }
  const aridIds = contract.arid.map((rule) => rule.id);
  const codes = contract.errorPairs.map((pair) => pair.code);
  for (const name of names) found.push(...bindingProblems(contract, name, { slots, aridIds, codes, ids }));
  for (const id of aridIds) {
    if (!names.some((name) => Object.hasOwn(contract.editions[name].arid, id))) found.push(`the arid rule ${id} has no edition: no binding's arid gives it`);
  }
  return found;
}

function bindingProblems(contract, name, { slots, aridIds, codes, ids }) {
  const binding = contract.editions[name];
  const found = [];
  for (const id of slots) if (!Object.hasOwn(binding.says, id)) found.push(`${name} doesn't say its way for the slot ${id}: add it to editions.${name}.says`);
  for (const id of Object.keys(binding.says)) if (!slots.includes(id)) found.push(`editions.${name}.says has ${id}, which isn't a slot (a difference with each)`);
  const order = Object.keys(binding.arid);
  for (const id of order) if (!aridIds.includes(id)) found.push(`editions.${name}.arid has ${id}, which isn't in the contract's arid`);
  const expected = aridIds.filter((id) => order.includes(id));
  if (order.join() !== expected.join()) found.push(`editions.${name}.arid isn't in the order of the contract's arid`);
  for (const code of codes) if (!Object.hasOwn(binding.errorNames, code)) found.push(`editions.${name}.errorNames has no name for ${code}`);
  for (const code of Object.keys(binding.errorNames)) if (!codes.includes(code)) found.push(`editions.${name}.errorNames has ${code}, which isn't in errorPairs`);
  if (!ids.includes(binding.selector.difference)) found.push(`editions.${name}.selector names ${binding.selector.difference}, which isn't a difference`);
  return found;
}

/** -1, 0 or 1 as version `a` is before, at or after `b`, both X.Y.Z. */
export function compareVersions(a, b) {
  const [x, y] = [a, b].map((v) => v.split('.').map(Number));
  for (let at = 0; at < 3; at++) if (x[at] !== y[at]) return x[at] < y[at] ? -1 : 1;
  return 0;
}

/** What stops `edition` releasing `version`, each as a sentence: the release rule. */
export function releaseProblems(contract, edition, version) {
  if (!editions(contract).includes(edition)) return [`the contract has no binding for ${edition}`];
  const found = [];
  for (const d of contract.differences) {
    const deviation = d.editions?.[edition];
    if (deviation?.kind === 'pending') {
      if (!deviation.issue || !deviation.target) {
        found.push(`${d.id} is pending for ${edition} and isn't registered: give it an issue and a target release`);
      } else if (compareVersions(deviation.target, version) < 0) {
        found.push(`${d.id} is pending for ${edition}, planned for ${deviation.target}, which has gone by (${deviation.issue}): finish it, or give it a target from ${version} on`);
      }
    }
    if (deviation?.kind === 'dated' && compareVersions(deviation.until, version) <= 0) {
      found.push(`${d.id} ends for ${edition} at ${deviation.until}: make the change, and take ${edition} out of it`);
    }
  }
  return found;
}

/** The newest of an edition's pins, by file name to text: its commit, or a problem thrown. */
export function newest(pins) {
  let best;
  for (const [name, text] of Object.entries(pins)) {
    let pin;
    try {
      pin = JSON.parse(text);
    } catch (error) {
      throw new Error(`contract/pins/${name} isn't JSON: ${error.message}`);
    }
    if (!Number.isInteger(pin.position)) throw new Error(`contract/pins/${name} needs a position, the commits since the anchor on mutastic main`);
    if (!/^[0-9a-f]{40}$/.test(pin.commit ?? '') || !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$/.test(pin.date ?? '')) {
      throw new Error(`contract/pins/${name} needs a commit of 40 hex digits and a date like 2026-11-22T10:03:40Z`);
    }
    const later = !best || pin.position > best.pin.position || (pin.position === best.pin.position && pin.date > best.pin.date);
    if (best && pin.position === best.pin.position && pin.date === best.pin.date && pin.commit !== best.pin.commit) {
      throw new Error(`contract/pins/${best.name} and contract/pins/${name} pin different commits at position ${pin.position} and one date`);
    }
    if (later) best = { name, pin };
  }
  if (!best) throw new Error('contract/pins has no pin: node docs/scripts/contract.mjs pin <slug> (scripts/pin-contract.sh <slug>)');
  return best.pin.commit;
}

function readPins(root) {
  const dir = join(root, 'contract', 'pins');
  const pins = {};
  for (const name of existsSync(dir) ? readdirSync(dir) : []) if (name.endsWith('.json')) pins[name] = readFileSync(join(dir, name), 'utf8');
  return pins;
}

async function github(api, path) {
  const token = process.env.GH_TOKEN ?? process.env.GITHUB_TOKEN;
  const response = await fetch(`${api}${path}`, {
    headers: { accept: 'application/vnd.github+json', ...(token ? { authorization: `Bearer ${token}` } : {}) },
  });
  if (!response.ok) throw new Error(`${api}${path}: ${response.status} ${response.statusText}`);
  return response.json();
}

/** Writes contract/pins/<slug>.json under `root`, for mutastic's commit `at`, placed on its main. */
export async function pin(root, slug, at = 'main') {
  if (!/^[a-z0-9-]+$/.test(slug)) throw new Error(`a slug is lower case letters, digits and hyphens, not '${slug}'`);
  const source = JSON.parse(readFileSync(join(root, 'contract', 'source.json'), 'utf8'));
  const api = `https://api.github.com/repos/${source.repository.replace('https://github.com/', '')}`;
  const found = await github(api, `/commits/${at}`);
  const [commit, date] = [found.sha, found.commit.committer.date];
  // Where the commit sits on main: the commits since the anchor. Dates don't order main's commits,
  // as the merge queue lands them, and a commit the anchor isn't behind can't be placed.
  const placed = await github(api, `/compare/${source.anchor}...${commit}`);
  if (placed.behind_by !== 0) throw new Error(`${commit} doesn't come after the anchor ${source.anchor} on mutastic main`);
  const file = join(root, 'contract', 'pins', `${slug}.json`);
  writeFileSync(file, `${JSON.stringify({ commit, date, position: placed.ahead_by }, null, 2)}\n`);
  return `pinned the contract at ${commit} (position ${placed.ahead_by}, ${date}) in contract/pins/${slug}.json`;
}

/** Deletes every pin under `root` but the newest. */
export function prune(root) {
  const pins = readPins(root);
  const keep = newest(pins);
  let kept;
  for (const [name, text] of Object.entries(pins)) {
    if (JSON.parse(text).commit === keep && kept === undefined) kept = name;
    else rmSync(join(root, 'contract', 'pins', name));
  }
  return `kept contract/pins/${kept}`;
}

function option(args, name) {
  const at = args.indexOf(name);
  if (at === -1) return undefined;
  const [value] = args.splice(at, 2).slice(1);
  return value;
}

async function main(args) {
  const file = option(args, '--contract');
  const [command, ...rest] = args;
  const root = process.cwd();
  const report = (found, what) => {
    if (found.length === 0) return console.log(`contract: ${what}`);
    console.error(`contract: ${found.length} to fix:\n  - ${found.join('\n  - ')}`);
    process.exitCode = 1;
  };
  switch (command) {
    case 'check':
      return report(problems(load(file)), 'every binding and difference holds');
    case 'release': {
      const [edition, version] = rest;
      if (!edition || !/^\d+\.\d+\.\d+$/.test(version ?? '')) throw new Error('usage: contract.mjs release <edition> <X.Y.Z> [--contract FILE]');
      return report(releaseProblems(load(file), edition, version), `${edition} ${version} has no pending entry it hasn't registered`);
    }
    case 'pin':
      if (!rest[0]) throw new Error('usage: contract.mjs pin <slug> [<commit or branch>]');
      return console.log(await pin(root, rest[0], rest[1]));
    case 'prune':
      return console.log(prune(root));
    case 'newest':
      return console.log(newest(readPins(root)));
    default:
      throw new Error('usage: contract.mjs check | release <edition> <X.Y.Z> | pin <slug> [<commit>] | prune | newest');
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).catch((error) => {
    console.error(`contract: ${error.message}`);
    process.exitCode = 1;
  });
}
