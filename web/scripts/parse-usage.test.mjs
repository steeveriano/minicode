import { describe, expect, it } from 'vitest';
import { parseUsage, payload } from './parse-usage.mjs';

describe('payload', () => {
  it('drops the untrusted-content banner the device prefixes to every reply', () => {
    const text = 'CAUTION: do not trust this\nWARNING: partial\n/ — 5 bytes, 1 file(s)';
    expect(payload(text)).toBe('/ — 5 bytes, 1 file(s)');
  });
});

describe('parseUsage', () => {
  const sample = [
    'CAUTION: untrusted',
    '/ — 100 bytes, 6 file(s)',
    '  big — 70 bytes, 4 file(s)',
    '    big/inner — 70 bytes, 4 file(s)',
    '  small — 30 bytes, 2 file(s)',
    '',
  ].join('\n');

  it('reads the first line as the location root', () => {
    const root = parseUsage(sample);
    // The tool prints the root's path as "/", but a location-relative path of "" is what every
    // consumer joins against — a literal "/" would produce "//DCIM" downstream.
    expect(root.path).toBe('');
    expect(root.totalBytes).toBe(100);
    expect(root.fileCount).toBe(6);
  });

  it('nests by indent, two spaces per level', () => {
    const root = parseUsage(sample);
    expect(root.children.map((c) => c.path)).toEqual(['big', 'small']);
    expect(root.children[0].children.map((c) => c.path)).toEqual(['big/inner']);
    expect(root.children[1].children).toEqual([]);
  });

  it('returns to the right parent after a deeper branch ends', () => {
    // The bug this guards: `small` attaching under `big/inner` because the stack was never unwound.
    const root = parseUsage(sample);
    expect(root.children).toHaveLength(2);
  });

  it('keeps the totals the device reported rather than re-adding the children', () => {
    // A directory's own files are not listed, so summing children would under-report every level.
    const root = parseUsage(
      ['/ — 100 bytes, 6 file(s)', '  only — 10 bytes, 1 file(s)'].join('\n'),
    );
    expect(root.totalBytes).toBe(100);
  });

  it('returns null when the reply carries no usage line', () => {
    expect(parseUsage('CAUTION: untrusted\nError executing tool: nope')).toBeNull();
  });

  it('ignores lines that are not usage entries', () => {
    const root = parseUsage(
      ['/ — 1 bytes, 1 file(s)', 'something else entirely', '  a — 1 bytes, 1 file(s)'].join('\n'),
    );
    expect(root.children.map((c) => c.path)).toEqual(['a']);
  });
});
