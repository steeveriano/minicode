/**
 * The thumbnail cache — how a thumbnail is named.
 *
 * A tile view of a folder with two hundred photos is two hundred trips across the tunnel, and the
 * free allowance is one gigabyte a month — a single folder would spend a tenth of it on one look.
 * So the full image crosses once, is shrunk in the browser, and the small copy is kept in a private
 * bucket. Every later view of that file, from any machine and any session, reads the copy.
 *
 * Naming lives here, apart from the bucket, so the rule that decides when a cached thumbnail has
 * gone stale can be tested without a network or a browser behind it.
 *
 * The key is a fingerprint of *where the file is and what shape it has* — device, location, path,
 * size, modification time — not of its bytes. It is deliberately not the engine's
 * `media_id = sha256(bytes)`: we do not have the bytes when we need the key, and this one answers a
 * different question. It changes when the file is edited or replaced, which is exactly when the
 * cached thumbnail stopped being true.
 */

/** The longest edge of a stored thumbnail. Enough for a tile, small enough to be free to move. */
export const THUMB_EDGE = 240;

export type ThumbSubject = {
  slug: string;
  locationId: string;
  /** Relative to the location root — the form the device's own tools accept. */
  path: string;
  sizeBytes: number;
  lastModified: number | null;
};

/**
 * The exact string that gets hashed.
 *
 * Encoded as JSON rather than joined with a separator: a path may contain any character, and a
 * delimiter that can appear inside a field is a collision waiting to happen.
 */
export function thumbFingerprint(subject: ThumbSubject): string {
  return JSON.stringify([
    subject.slug,
    subject.locationId,
    subject.path,
    subject.sizeBytes,
    subject.lastModified ?? 0,
  ]);
}

export async function thumbKey(subject: ThumbSubject): Promise<string> {
  const bytes = new TextEncoder().encode(thumbFingerprint(subject));
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

/**
 * Where the thumbnail lives in the bucket.
 *
 * Fanned out by the first byte of the key: a bucket with tens of thousands of objects in one flat
 * prefix is slow to list and unpleasant to inspect by hand.
 */
export function thumbPath(slug: string, key: string): string {
  return `${slug}/${key.slice(0, 2)}/${key}.jpg`;
}

/** Scales a box down to fit inside `max` on its longest edge. Never scales up. */
export function fitWithin(width: number, height: number, max: number): { width: number; height: number } {
  const longest = Math.max(width, height);
  if (longest <= max || longest === 0) return { width, height };
  const factor = max / longest;
  return {
    width: Math.max(1, Math.round(width * factor)),
    height: Math.max(1, Math.round(height * factor)),
  };
}
