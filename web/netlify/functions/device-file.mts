import type { Config } from '@netlify/functions';
import { DEVICE_TIMEOUT_MS, bearerFrom, callDeviceTool, deviceFor, gateFor, json } from './lib/device.mts';

/**
 * Netlify answers a synchronous function with at most 6 MB, so anything near that is refused rather
 * than truncated. Thumbnails and documents fit comfortably; a video does not, and saying so is more
 * useful than a broken image.
 */
const MAX_PREVIEW_BYTES = 5_000_000;

/** `File 'name' image/png (24526 bytes) at https://… (expires 1h).` — the tool answers in prose. */
const SHARE_LINE = /at (https:\/\/\S+?) \(expires/;
const SHARE_META = /\(([0-9]+) bytes\)/;

/**
 * Serves one file from a device, for the preview pane.
 *
 * The device can expose a file at a temporary unauthenticated URL. That URL never reaches the
 * browser: this function fetches it server-side and re-serves the bytes from this origin, which is
 * both what the page's `img-src 'self'` allows and what keeps an open link out of a page someone
 * might share.
 */
export default async (req: Request): Promise<Response> => {
  if (req.method !== 'GET') return json(405, { error: 'Solo se admite GET.' });

  const accessToken = bearerFrom(req);
  if (!accessToken) return json(401, { error: 'Falta la sesión.' });

  const url = new URL(req.url);
  const slug = url.pathname.split('/').filter(Boolean)[2] ?? '';
  const locationId = url.searchParams.get('location') ?? '';
  const path = url.searchParams.get('path') ?? '';
  if (!locationId || !path) return json(400, { error: 'Faltan «location» y «path».' });

  let level: Awaited<ReturnType<typeof gateFor>>;
  try {
    level = await gateFor(accessToken, slug);
  } catch {
    return json(503, { error: 'No se pudo verificar la sesión.' });
  }
  if (!level) return json(403, { error: 'Sin acceso.' });

  const device = deviceFor(slug);
  if (!device) return json(404, { error: 'Dispositivo sin endpoint configurado.' });

  let announcement: string;
  try {
    announcement = await callDeviceTool(device, 'android_share_file_via_web', {
      location_id: locationId,
      path,
    });
  } catch (error) {
    const timedOut = error instanceof Error && error.name === 'TimeoutError';
    return json(timedOut ? 504 : 502, {
      error: timedOut ? 'El dispositivo no respondió a tiempo.' : 'El dispositivo no pudo abrir el archivo.',
    });
  }

  const link = SHARE_LINE.exec(announcement)?.[1];
  if (!link) {
    // The tool refused, and its own message says why — a missing file, an unauthorized location.
    return json(422, { error: announcement.trim().slice(0, 300) || 'El archivo no se pudo exponer.' });
  }

  const declared = Number(SHARE_META.exec(announcement)?.[1] ?? '0');
  if (declared > MAX_PREVIEW_BYTES) {
    return json(413, {
      error: 'Demasiado grande para previsualizar acá.',
      bytes: declared,
    });
  }

  let upstream: Response;
  try {
    upstream = await fetch(link, { signal: AbortSignal.timeout(DEVICE_TIMEOUT_MS) });
  } catch {
    return json(504, { error: 'El archivo no llegó a tiempo.' });
  }
  if (!upstream.ok) return json(502, { error: 'El dispositivo no entregó el archivo.' });

  const bytes = await upstream.arrayBuffer();
  if (bytes.byteLength > MAX_PREVIEW_BYTES) {
    return json(413, { error: 'Demasiado grande para previsualizar acá.', bytes: bytes.byteLength });
  }

  return new Response(bytes, {
    status: 200,
    headers: {
      // Trust the device's own content type: it knows the file, and sniffing here would only add a
      // second opinion. `nosniff` keeps the browser from forming a third.
      'content-type': upstream.headers.get('content-type') ?? 'application/octet-stream',
      'content-length': String(bytes.byteLength),
      'x-content-type-options': 'nosniff',
      // Private and short: the panel re-renders often, and this is one person's file.
      'cache-control': 'private, max-age=300',
      'content-disposition': 'inline',
    },
  });
};

export const config: Config = { path: '/api/device/:slug/file' };
