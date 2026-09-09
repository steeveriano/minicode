import type { Config } from '@netlify/functions';
import { DEVICE_TIMEOUT_MS, bearerFrom, deviceFor, gateFor, json, rejectionReason } from './lib/device.mts';

/**
 * Authenticated proxy from the panel to one device's MCP server.
 *
 * Browser → this function → tunnel → phone. The browser sends its Supabase session; the device's
 * bearer token is added here and never leaves the server. A caller who is not an allowlisted,
 * confirmed viewer gets 403 before any request reaches the device.
 */
export default async (req: Request): Promise<Response> => {
  if (req.method !== 'POST') return json(405, { error: 'Solo se admite POST.' });

  const accessToken = bearerFrom(req);
  if (!accessToken) return json(401, { error: 'Falta la sesión.' });

  const slug = new URL(req.url).pathname.split('/').filter(Boolean)[2] ?? '';

  let level: Awaited<ReturnType<typeof gateFor>>;
  try {
    level = await gateFor(accessToken, slug);
  } catch {
    return json(503, { error: 'No se pudo verificar la sesión.' });
  }
  // One message for "not signed in", "not on the list" and "no such device": none of the three is
  // distinguishable from outside.
  if (!level) return json(403, { error: 'Sin acceso.' });

  const device = deviceFor(slug);
  if (!device) return json(404, { error: 'Dispositivo sin endpoint configurado.' });

  let payload: unknown;
  try {
    payload = await req.json();
  } catch {
    return json(400, { error: 'Cuerpo JSON inválido.' });
  }

  const rejection = rejectionReason(payload, level);
  if (rejection) return json(403, { error: rejection });

  const sessionId = req.headers.get('mcp-session-id');
  const headers: Record<string, string> = {
    authorization: `Bearer ${device.token}`,
    'content-type': 'application/json',
    accept: 'application/json, text/event-stream',
  };
  if (sessionId) headers['mcp-session-id'] = sessionId;

  let upstream: Response;
  try {
    upstream = await fetch(`${device.url}/mcp`, {
      method: 'POST',
      headers,
      body: JSON.stringify(payload),
      signal: AbortSignal.timeout(DEVICE_TIMEOUT_MS),
    });
  } catch (error) {
    // A timeout here is usually a walk that is too deep rather than a dead device, so say so:
    // the fix is to ask for less, not to retry the same request.
    const timedOut = error instanceof Error && error.name === 'TimeoutError';
    return json(504, {
      error: timedOut
        ? 'El dispositivo no respondió a tiempo. Pedí menos profundidad o un subárbol más chico.'
        : 'No se pudo alcanzar el dispositivo.',
    });
  }

  const body = await upstream.text();
  const contentType = upstream.headers.get('content-type') ?? 'application/json';

  // A tunnel with no agent on the far end answers with its own HTML error page, and passing that
  // through gives the panel a wall of markup where it expected a protocol reply. Anything that is
  // not JSON is therefore reported as what it actually is: the device is unreachable.
  if (!contentType.includes('json')) {
    return json(502, {
      error:
        upstream.status === 404
          ? 'El dispositivo no está conectado al túnel. Iniciá el servidor en el teléfono.'
          : 'El túnel respondió algo que no es del dispositivo.',
      upstreamStatus: upstream.status,
    });
  }

  const out: Record<string, string> = { 'content-type': contentType, 'cache-control': 'no-store' };
  const upstreamSession = upstream.headers.get('mcp-session-id');
  if (upstreamSession) out['mcp-session-id'] = upstreamSession;

  return new Response(body, { status: upstream.status, headers: out });
};

export const config: Config = { path: '/api/device/:slug/mcp' };
