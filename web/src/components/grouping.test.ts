import { describe, expect, it } from 'vitest';
import type { FileEntry } from '../api/device';
import { groupBytes, groupEntries } from './grouping';

const NOW = Date.UTC(2026, 8, 9, 12, 0, 0);
const DAY = 86_400_000;

function file(name: string, size = 0, modified: number | null = NOW): FileEntry {
  return { name, path: '', is_directory: false, size, last_modified: modified, mime_type: null };
}

function dir(name: string): FileEntry {
  return { name, path: '', is_directory: true, size: 0, last_modified: NOW, mime_type: null };
}

describe('groupEntries', () => {
  it('leaves the list flat when not grouping', () => {
    const entries = [file('a.jpg'), file('b.mp4')];
    const groups = groupEntries(entries, 'none', NOW);
    expect(groups).toHaveLength(1);
    expect(groups[0]?.label).toBe('');
    expect(groups[0]?.entries).toEqual(entries);
  });

  it('groups by family with folders first', () => {
    const groups = groupEntries([file('a.jpg'), dir('Camera'), file('b.mp4')], 'type', NOW);
    expect(groups.map((g) => g.label)).toEqual(['Carpetas', 'Imágenes', 'Vídeos']);
  });

  it('orders sections by their band, not by first appearance', () => {
    // Video appears first in the input; images must still come first in the output.
    const groups = groupEntries([file('b.mp4'), file('a.jpg')], 'type', NOW);
    expect(groups[0]?.label).toBe('Imágenes');
  });

  it('preserves the incoming order inside a section', () => {
    const groups = groupEntries([file('z.jpg'), file('a.jpg')], 'type', NOW);
    expect(groups[0]?.entries.map((e) => e.name)).toEqual(['z.jpg', 'a.jpg']);
  });

  it('bands by size and keeps folders out of the empty band', () => {
    const groups = groupEntries(
      [file('empty.txt', 0), file('small.jpg', 50_000), file('huge.mp4', 200_000_000), dir('Camera')],
      'size',
      NOW,
    );
    expect(groups[0]?.label).toBe('Carpetas');
    expect(groups.map((g) => g.label)).toContain('Vacíos');
    expect(groups.map((g) => g.label)).toContain('Enormes · 100 MB o más');
  });

  it('bands by age, and a file with no timestamp gets its own section', () => {
    const groups = groupEntries(
      [file('today.jpg', 1, NOW), file('old.jpg', 1, NOW - 400 * DAY), file('unknown.jpg', 1, null)],
      'date',
      NOW,
    );
    expect(groups.map((g) => g.label)).toEqual(['Hoy', 'Más de un año', 'Sin fecha']);
  });

  it('puts a file from six days ago in this week, not today', () => {
    const groups = groupEntries([file('a.jpg', 1, NOW - 6 * DAY)], 'date', NOW);
    expect(groups[0]?.label).toBe('Esta semana');
  });

  it('returns nothing for an empty list', () => {
    expect(groupEntries([], 'type', NOW)).toEqual([]);
  });
});

describe('groupBytes', () => {
  it('sums only files, since a folder has no size until measured', () => {
    const group = { key: 'k', label: 'l', entries: [file('a.jpg', 100), dir('d'), file('b.jpg', 50)] };
    expect(groupBytes(group)).toBe(150);
  });
});
