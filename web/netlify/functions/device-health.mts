import type { Config } from '@netlify/functions';
import { bearerFrom, deviceFor, isViewer, json } from './lib/device.mts';

/** How long to wait on a health probe. Short on purpose: this answers "is it up", not "do work". */
const HEALTH_TIMEOUT_MS = 8_000;

/**
 * Reachability of one device, answered from the device itself rather than from a stored flag.
 *
 * The device's `/health` endpoint is unauthenticated, but this route is not: the panel is a private
 * inventory, and whether a given phone is online is itself information about its owner.
 */
export default async (req: Request): Promise<Response> => {
  if (req.method !== 'GET') return json(405, { error: 'Solo se admite GET.' });

  const accessToken = bearerFrom(req);
  if (!accessToken) return json(401, { error: 'Falta la sesión.' });

  let viewer: boolean;
  try {
    viewer = await isViewer(accessToken);
  } catch {
    return json(503, { error: 'No se pudo verificar la sesión.' });
  }
  if (!viewer) return json(403, { error: 'Sin acceso.' });

  const slug = new URL(req.url).pathname.split('/').filter(Boolean)[2] ?? '';
  const device = deviceFor(slug);
  if (!device) return json(404, { error: 'Dispositivo desconocido.' });

  const startedAt = Date.now();
  try {
    const upstream = await fetch(`${device.url}/health`, {
      signal: AbortSignal.timeout(HEALTH_TIMEOUT_MS),
    });
    const elapsedMs = Date.now() - startedAt;

    if (!upstream.ok) {
      // ngrok answers 404 for a domain whose agent is not connected: the tunnel exists, the phone
      // is not on the other end of it. That is a different problem from a broken URL, and the
      // panel should be able to say which.
      return json(200, { slug, status: 'offline', elapsedMs, upstreamStatus: upstream.status });
    }

    let version: string | null = null;
    try {
      const parsed = (await upstream.json()) as { version?: unknown };
      if (typeof parsed.version === 'string') version = parsed.version;
    } catch {
      version = null;
    }
    return json(200, { slug, status: 'online', elapsedMs, version });
  } catch {
    return json(200, { slug, status: 'offline', elapsedMs: Date.now() - startedAt });
  }
};

export const config: Config = { path: '/api/device/:slug/health' };
