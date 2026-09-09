import type { Config } from '@netlify/functions';
import { DEVICE_TIMEOUT_MS, bearerFrom, callDeviceTool, deviceFor, gateFor, json } from './lib/device.mts';

/**
 * The device refuses to expose anything over 64 MB, so that is the real ceiling and this is only a
 * guard against a device that changes its mind.
 *
 * An earlier version capped at 5 MB because it read the whole file into memory before answering,
 * and a buffered function response is limited to 6 MB. Streaming the body through removes that
 * limit entirely — the bytes never sit in the function at all.
 */
const MAX_PREVIEW_BYTES = 67_108_864;

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
      error: timedOut
        ? 'El dispositivo no respondió a tiempo.'
        : 'El dispositivo no pudo abrir el archivo. Si el túnel está arriba, revisá que «Share File via Web» esté activada en Ajustes → Herramientas MCP.',
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
  if (!upstream.ok) {
    return json(502, {
      error:
        upstream.status === 404
          ? 'El dispositivo no está conectado al túnel. Iniciá el servidor en el teléfono.'
          : 'El dispositivo no entregó el archivo.',
    });
  }

  if (!upstream.body) return json(502, { error: 'El dispositivo no entregó contenido.' });

  const headers: Record<string, string> = {
    // Trust the device's own content type: it knows the file, and sniffing here would only add a
    // second opinion. `nosniff` keeps the browser from forming a third.
    'content-type': upstream.headers.get('content-type') ?? 'application/octet-stream',
    'x-content-type-options': 'nosniff',
    // Private and short: the panel re-renders often, and this is one person's file.
    'cache-control': 'private, max-age=300',
    'content-disposition': 'inline',
  };
  const length = upstream.headers.get('content-length');
  if (length) headers['content-length'] = length;

  // Piped, not buffered. The bytes pass through without the function ever holding the file, which
  // is what lets a 60 MB video answer at all. The device ignores Range and always sends the whole
  // file, so there is no seeking — playback starts at the beginning and that is the honest limit.
  return new Response(upstream.body, { status: 200, headers });
};

export const config: Config = { path: '/api/device/:slug/file' };
