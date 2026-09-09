import { describe, expect, it } from 'vitest';
import { fitWithin, thumbFingerprint, thumbKey, thumbPath, type ThumbSubject } from './thumbKey';

const base: ThumbSubject = {
  slug: 'celular',
  locationId: 'builtin:dcim',
  path: 'Camera/IMG_0001.jpg',
  sizeBytes: 2_400_000,
  lastModified: 1_757_000_000_000,
};

describe('thumbFingerprint', () => {
  it('changes when the file is replaced at the same path', () => {
    expect(thumbFingerprint(base)).not.toBe(thumbFingerprint({ ...base, sizeBytes: 2_400_001 }));
    expect(thumbFingerprint(base)).not.toBe(thumbFingerprint({ ...base, lastModified: 1 }));
  });

  it('separates the same path on two devices', () => {
    expect(thumbFingerprint(base)).not.toBe(thumbFingerprint({ ...base, slug: 'otro' }));
  });

  it('separates the same path in two locations', () => {
    expect(thumbFingerprint(base)).not.toBe(
      thumbFingerprint({ ...base, locationId: 'builtin:pictures' }),
    );
  });

  it('does not collide when a separator appears inside a field', () => {
    // The reason for JSON rather than joining: a path may contain any character, so a delimiter
    // that can occur inside a field would let two different files share one key.
    const a = { ...base, path: 'a/b', locationId: 'x' };
    const b = { ...base, path: 'b', locationId: 'x/a' };
    expect(thumbFingerprint(a)).not.toBe(thumbFingerprint(b));
  });

  it('treats a missing modification time as zero rather than dropping the field', () => {
    expect(thumbFingerprint({ ...base, lastModified: null })).toBe(
      thumbFingerprint({ ...base, lastModified: 0 }),
    );
  });
});

describe('thumbKey', () => {
  it('is a stable sha-256 hex digest', async () => {
    const key = await thumbKey(base);
    expect(key).toMatch(/^[0-9a-f]{64}$/);
    expect(await thumbKey({ ...base })).toBe(key);
  });
});

describe('thumbPath', () => {
  it('fans out by the first byte of the key', async () => {
    const key = await thumbKey(base);
    expect(thumbPath('celular', key)).toBe(`celular/${key.slice(0, 2)}/${key}.jpg`);
  });
});

describe('fitWithin', () => {
  it('leaves a box already inside the bound untouched', () => {
    expect(fitWithin(100, 80, 240)).toEqual({ width: 100, height: 80 });
  });

  it('scales the longest edge down to the bound', () => {
    expect(fitWithin(4000, 3000, 240)).toEqual({ width: 240, height: 180 });
    expect(fitWithin(3000, 4000, 240)).toEqual({ width: 180, height: 240 });
  });

  it('never rounds a dimension away to nothing', () => {
    expect(fitWithin(10_000, 3, 240)).toEqual({ width: 240, height: 1 });
  });

  it('survives a zero-sized image instead of dividing by it', () => {
    expect(fitWithin(0, 0, 240)).toEqual({ width: 0, height: 0 });
  });
});
