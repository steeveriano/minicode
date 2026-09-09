import { supabase } from '../supabase';
import { THUMB_EDGE, fitWithin, thumbKey, thumbPath, type ThumbSubject } from './thumbKey';

/**
 * The thumbnail cache — the bucket, and the work of filling it.
 *
 * Naming and staleness live in `thumbKey.ts`; this module is the half that talks to storage and to
 * the browser's image decoder. Both are re-exported so a caller has one import to think about.
 */

export * from './thumbKey';

const BUCKET = 'thumbs';

/** The bucket refuses anything larger, so an oversized encode is dropped rather than sent. */
const MAX_THUMB_BYTES = 524_288;

/** How many thumbnails stay in memory. Bounded: browsing 17 000 photos must not grow without end. */
const MEMORY_LIMIT = 500;

/** Blob for a hit, `null` for a miss we already looked up and need not look up again. */
const memory = new Map<string, Blob | null>();

function remember(key: string, value: Blob | null): void {
  if (memory.size >= MEMORY_LIMIT) {
    const oldest = memory.keys().next();
    if (!oldest.done) memory.delete(oldest.value);
  }
  memory.set(key, value);
}

/** The stored thumbnail, or null when there is none. A miss is remembered so it is asked once. */
export async function getThumb(slug: string, key: string): Promise<Blob | null> {
  const held = memory.get(key);
  if (held !== undefined) return held;

  const { data, error } = await supabase.storage.from(BUCKET).download(thumbPath(slug, key));
  // A missing object and a denied read both land here. Neither is worth interrupting a listing
  // for: the pane falls back to fetching the original, which is the behaviour without any cache.
  if (error || !data) {
    remember(key, null);
    return null;
  }
  remember(key, data);
  return data;
}

/** Stores a thumbnail. Failure is swallowed: a cache that cannot write must not break a preview. */
export async function putThumb(slug: string, key: string, blob: Blob): Promise<boolean> {
  if (blob.size > MAX_THUMB_BYTES) return false;
  const { error } = await supabase.storage.from(BUCKET).upload(thumbPath(slug, key), blob, {
    contentType: 'image/jpeg',
    upsert: true,
  });
  if (error) return false;
  remember(key, blob);
  return true;
}

/**
 * Shrinks an image to a thumbnail, in the browser.
 *
 * Done here rather than in a function because the first viewer pays the full download either way —
 * the tunnel trip is unavoidable exactly once — and doing it client-side needs no image library on
 * the server and spreads the work across whoever happens to open the folder.
 *
 * Returns null for anything the browser cannot decode as an image, which is the honest answer for
 * video and for a file whose extension lied about its contents.
 */
export async function renderThumb(source: Blob, max = THUMB_EDGE): Promise<Blob | null> {
  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(source);
  } catch {
    return null;
  }
  try {
    const size = fitWithin(bitmap.width, bitmap.height, max);
    const canvas = document.createElement('canvas');
    canvas.width = size.width;
    canvas.height = size.height;
    const context = canvas.getContext('2d');
    if (!context) return null;
    context.drawImage(bitmap, 0, 0, size.width, size.height);
    return await new Promise<Blob | null>((resolve) => {
      canvas.toBlob((out) => resolve(out), 'image/jpeg', 0.72);
    });
  } finally {
    bitmap.close();
  }
}

/**
 * How many originals may be in flight at once while filling the cache.
 *
 * The proxy gives each request about twenty seconds and the phone is one device serving one radio;
 * firing a folder's worth of downloads at it does not make them arrive sooner, it makes them time
 * out together.
 */
const CONCURRENCY = 3;

let running = 0;
const waiting: (() => void)[] = [];

async function slot<T>(work: () => Promise<T>): Promise<T> {
  if (running >= CONCURRENCY) await new Promise<void>((resolve) => waiting.push(resolve));
  running += 1;
  try {
    return await work();
  } finally {
    running -= 1;
    waiting.shift()?.();
  }
}

/** Downloads under way, by key, so the same original is never fetched twice at once. */
const inflight = new Map<string, Promise<Blob | null>>();

let spentBytes = 0;
let generatedCount = 0;

/**
 * What filling the cache has cost the tunnel so far, this session.
 *
 * Published because the allowance is finite and a number nobody can see is a number nobody can
 * govern: the status bar shows it while the tile view is open.
 */
export function thumbSpend(): { bytes: number; generated: number } {
  return { bytes: spentBytes, generated: generatedCount };
}

/**
 * The thumbnail for a file: from the bucket if it is there, otherwise made from the original.
 *
 * `fetchOriginal` is passed in rather than imported so this module stays independent of the device
 * client — and so a caller that already holds the bytes can hand them over instead of a second trip.
 */
export async function ensureThumb(
  subject: ThumbSubject,
  fetchOriginal: () => Promise<Blob>,
): Promise<Blob | null> {
  const key = await thumbKey(subject);
  const cached = await getThumb(subject.slug, key);
  if (cached) return cached;

  // One download per file, however many callers ask at once. Without this a re-mounted tile — which
  // React does on purpose in development — pays for the same original twice.
  const already = inflight.get(key);
  if (already) return already;

  const work = slot(async () => {
    const original = await fetchOriginal();
    spentBytes += original.size;
    const small = await renderThumb(original);
    if (!small) {
      remember(key, null);
      return null;
    }
    generatedCount += 1;
    await putThumb(subject.slug, key, small);
    return small;
  }).finally(() => inflight.delete(key));

  inflight.set(key, work);
  return work;
}
