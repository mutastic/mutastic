// The contract's tooling, and the proof that it takes another edition cleanly: a dummy `swift`
// edition (test data here, never shipped) joins with a binding block, a fixture's capture, a pin
// and its own differences, and no shared entry is edited.
//
//   node --test docs/scripts/contract.test.mjs
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { captureProblems, mask, masks, readMapping } from './compare-captures.mjs';
import { differencesOf, editions, load, newest, problems, prune, releaseProblems } from './contract.mjs';

const shipped = load();
const copy = (value) => JSON.parse(JSON.stringify(value));

/** Swift's binding block: everything a third language is asked for, and nothing else. */
function swiftBinding(contract) {
  const slots = contract.differences.filter((d) => d.each === true).map((d) => d.id);
  return {
    name: 'Swift',
    repository: 'https://github.com/mutastic/mutastic-swift',
    contract: { reads: 'pin', pins: 'contract/pins' },
    selector: { unit: 'a SwiftPM product or an Xcode scheme', slot: '[--scheme=SCHEME]', difference: 'N1' },
    buildTools: ['SwiftPM', 'Xcode'],
    sources: { languages: ['Swift'], extensions: ['swift'] },
    testLibraries: ['Swift Testing', 'XCTest'],
    fixture: 'fixtures/pricing',
    captures: 'docs/output',
    masks: {
      toolchain: 'Swift [\\d.]+',
      work: '.build/mutastic',
      unit: 'Files',
      level: 'source',
      tests: '\\w+Tests',
      failed: 'failed',
      function: 'function',
      code: ['@Test', 'func ', 'let ', 'import ', '#expect'],
    },
    // Decision 12: a new edition uses the generic names.
    errorNames: Object.fromEntries(contract.errorPairs.map(({ code }) => [code, code])),
    arid: {
      'mutastic:output': { reasons: ['arid: only writes output'], example: 'print("saved \\(id)")' },
    },
    twins: ['A returned comparison made `true` or `false` by two operators.'],
    says: Object.fromEntries(slots.map((id) => [id, `Swift's way for ${id}`])),
  };
}

/** Swift's own differences: what it deviates in, each naming only swift. */
const swiftDifferences = [
  {
    id: 'S1',
    editions: { swift: { kind: 'engine' } },
    surface: 'command line',
    summary: '`--destination=DEST`',
    reason: "Xcode's destinations: a simulator or a Mac.",
    items: ['option:--destination', 'takes:*:--destination'],
  },
  {
    id: 'S2',
    editions: { swift: { kind: 'pending', issue: 'https://github.com/mutastic/mutastic-swift/issues/1', target: '1.6.0' } },
    surface: 'human output',
    summary: 'The `Arid audit` row',
    items: ['row:Arid audit'],
  },
];

/** The shipped contract with Swift added as a new edition adds itself. */
function withSwift() {
  const contract = copy(shipped);
  contract.editions.swift = swiftBinding(contract);
  contract.differences.push(...copy(swiftDifferences));
  return contract;
}

test('the shipped contract holds', () => {
  assert.deepEqual(problems(shipped), []);
  assert.deepEqual(editions(shipped), ['java', 'rust']);
});

test('a third edition is a binding block and its differences, with no shared entry edited', () => {
  const contract = withSwift();
  assert.deepEqual(problems(contract), []);
  for (const key of Object.keys(shipped)) {
    if (key === 'editions' || key === 'differences') continue;
    assert.deepEqual(contract[key], shipped[key], `the shared entry ${key}`);
  }
  for (const name of editions(shipped)) assert.deepEqual(contract.editions[name], shipped.editions[name], `${name}'s binding`);
  assert.deepEqual(contract.differences.slice(0, shipped.differences.length), shipped.differences, 'the differences already there');
  // Swift has every slot, its own deviations, and none of Java's or Rust's.
  const swift = differencesOf(contract, 'swift').map((d) => d.id);
  assert.ok(swift.includes('N12') && swift.includes('S1') && swift.includes('S2'));
  assert.ok(!swift.includes('N2') && !swift.includes('N4'), "Java's --tests-from and Rust's --unsafe aren't Swift's");
});

test('a binding that leaves out a slot, or names what the contract lacks, fails', () => {
  const contract = withSwift();
  delete contract.editions.swift.says.N12;
  contract.editions.swift.arid['mutastic:nothing-here'] = { reasons: ['arid: no'], example: 'x' };
  delete contract.editions.swift.errorNames['build-root'];
  contract.differences.push({ id: 'S3', editions: { kotlin: { kind: 'engine' } }, surface: 'x', summary: 'x', reason: 'x' });
  const found = problems(contract);
  assert.ok(found.some((p) => p.startsWith("swift doesn't say its way for the slot N12")), found.join('\n'));
  assert.ok(found.some((p) => p.includes('mutastic:nothing-here')));
  assert.ok(found.some((p) => p.includes('errorNames has no name for build-root')));
  assert.ok(found.some((p) => p.includes("S3 names 'kotlin'")));
});

test('no edition releases with a pending entry it has not registered', () => {
  const contract = withSwift();
  assert.deepEqual(releaseProblems(contract, 'swift', '1.5.0'), []);
  assert.deepEqual(releaseProblems(contract, 'swift', '1.6.0'), [], 'a target of this release still holds');
  assert.match(releaseProblems(contract, 'swift', '1.6.1')[0], /^S2 is pending for swift, planned for 1\.6\.0, which has gone by/);
  delete contract.differences.at(-1).editions.swift.target;
  assert.match(releaseProblems(contract, 'swift', '1.5.0')[0], /^S2 is pending for swift and isn't registered/);
  assert.match(problems(contract).join('\n'), /S2 is pending for swift without a target/);
  assert.match(releaseProblems(shipped, 'rust', '2.0.0')[0], /^N34 ends for rust at 2\.0\.0/);
  assert.deepEqual(releaseProblems(shipped, 'swift', '1.5.0'), ['the contract has no binding for swift']);
});

test("a pin is a file of the edition's own, and the newest wins", () => {
  const root = mkdtempSync(join(tmpdir(), 'contract-pins-'));
  mkdirSync(join(root, 'contract', 'pins'), { recursive: true });
  const pin = (name, commit, position, date) =>
    writeFileSync(join(root, 'contract', 'pins', name), `${JSON.stringify({ commit: commit.repeat(40), date, position }, null, 2)}\n`);
  pin('first.json', 'a', 3, '2026-11-26T10:00:00Z');
  pin('swift-joins.json', 'b', 7, '2026-11-25T10:00:00Z');
  pin('same-place.json', 'c', 7, '2026-11-26T10:00:00Z');
  const pins = Object.fromEntries(readdirSync(join(root, 'contract', 'pins')).map((n) => [n, `{"commit":"${'d'.repeat(40)}"}`]));
  assert.throws(() => newest(pins), /needs a position/);
  assert.equal(prune(root), 'kept contract/pins/same-place.json');
  assert.deepEqual(readdirSync(join(root, 'contract', 'pins')), ['same-place.json']);
});

test("a third edition's capture is masked by its binding and compared with the reference's", () => {
  const contract = withSwift();
  const m = masks(contract);
  assert.equal(mask('     Tests  3 on Swift 6.0 · 4 workers', m), ' tests <n> on <toolchain> · <n> worker');
  assert.equal(mask('Sources/Pricing/Pricing.swift:12:5 m855c8e3c', m), '<file> <id>');
  // Swift names its capture by the scenario (captures.json has no Swift name): Java's words, as
  // Swift would print them.
  const docs = mkdtempSync(join(tmpdir(), 'swift-docs-'));
  mkdirSync(join(docs, 'output'), { recursive: true });
  const scenarios = { samePath: [], scenarios: { 'errors-unknown-option': readMapping().scenarios['errors-unknown-option'] } };
  const java = readFileSync(new URL('../src/data/output/errors-unknown-option.ansi', import.meta.url), 'utf8');
  writeFileSync(join(docs, 'output', 'errors-unknown-option.txt'), java.replaceAll('samples/pricing', 'fixtures/pricing'));
  assert.deepEqual(captureProblems(docs, 'swift', contract, scenarios), []);
  // A Swift capture that says it otherwise fails until a difference naming swift records it.
  writeFileSync(join(docs, 'output', 'errors-unknown-option.txt'), 'mutastic: unknown option --verbose\n');
  assert.match(captureProblems(docs, 'swift', contract, scenarios)[0], /the swift capture errors-unknown-option differs from java's/);
  contract.differences.push({ id: 'S9', editions: { swift: { kind: 'engine' } }, surface: 'errors', summary: 'x', reason: 'x', items: ['capture:errors-unknown-option'] });
  assert.deepEqual(captureProblems(docs, 'swift', contract, scenarios), []);
});
