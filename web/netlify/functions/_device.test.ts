import { afterEach, describe, expect, it } from 'vitest';
import { ALLOWED_TOOLS, bearerFrom, deviceFor, rejectionReason } from './_device.mts';

const ENV_KEYS = ['DEVICE_CELULAR_URL', 'DEVICE_CELULAR_TOKEN', 'DEVICE_MI_PC_URL', 'DEVICE_MI_PC_TOKEN'];

afterEach(() => {
  for (const key of ENV_KEYS) delete process.env[key];
});

function call(name: string) {
  return { jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name, arguments: {} } };
}

describe('rejectionReason', () => {
  it('lets a plain tools/list through', () => {
    expect(rejectionReason({ jsonrpc: '2.0', id: 1, method: 'tools/list' })).toBeNull();
  });

  it('lets every allowed tool through', () => {
    for (const name of ALLOWED_TOOLS) expect(rejectionReason(call(name))).toBeNull();
  });

  it('blocks a destructive tool by name', () => {
    expect(rejectionReason(call('android_delete_file'))).toContain('android_delete_file');
  });

  it('blocks writing, moving and quarantine purging', () => {
    for (const name of [
      'android_write_file',
      'android_move_file',
      'android_purge_quarantine_batch',
      'android_download_from_url',
    ]) {
      expect(rejectionReason(call(name))).not.toBeNull();
    }
  });

  it('blocks tools that drive the screen', () => {
    for (const name of ['android_tap', 'android_swipe', 'android_type_replace_text', 'android_open_app']) {
      expect(rejectionReason(call(name))).not.toBeNull();
    }
  });

  it('blocks an unknown jsonrpc method', () => {
    expect(rejectionReason({ jsonrpc: '2.0', id: 1, method: 'resources/read' })).toContain('resources/read');
  });

  it('rejects a batch where one message is blocked', () => {
    // The case the allowlist exists for: a forbidden call riding along beside a permitted one.
    const batch = [{ jsonrpc: '2.0', id: 1, method: 'tools/list' }, call('android_delete_file')];
    expect(rejectionReason(batch)).toContain('android_delete_file');
  });

  it('accepts a batch where every message is allowed', () => {
    expect(rejectionReason([{ jsonrpc: '2.0', id: 1, method: 'tools/list' }, call('android_list_files')])).toBeNull();
  });

  it('rejects an empty batch', () => {
    expect(rejectionReason([])).toBe('cuerpo vacío');
  });

  it('rejects an oversized batch', () => {
    const many = Array.from({ length: 21 }, () => ({ jsonrpc: '2.0', id: 1, method: 'tools/list' }));
    expect(rejectionReason(many)).toBe('lote demasiado grande');
  });

  it('rejects a message that is not an object', () => {
    expect(rejectionReason('tools/list')).not.toBeNull();
    expect(rejectionReason(null)).not.toBeNull();
  });

  it('rejects tools/call without a tool name', () => {
    expect(rejectionReason({ jsonrpc: '2.0', id: 1, method: 'tools/call', params: {} })).not.toBeNull();
  });

  it('rejects a tool name that is not a string', () => {
    // A number here would sail past a naive `ALLOWED_TOOLS.has(name)` on a loosely typed input.
    expect(rejectionReason({ jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 7 } })).not.toBeNull();
  });
});

describe('deviceFor', () => {
  it('resolves a slug to its configured endpoint', () => {
    process.env.DEVICE_CELULAR_URL = 'https://example.ngrok-free.dev';
    process.env.DEVICE_CELULAR_TOKEN = 'token';
    expect(deviceFor('celular')).toEqual({
      slug: 'celular',
      url: 'https://example.ngrok-free.dev',
      token: 'token',
    });
  });

  it('maps hyphens in the slug to underscores in the variable name', () => {
    process.env.DEVICE_MI_PC_URL = 'https://pc.example';
    process.env.DEVICE_MI_PC_TOKEN = 'token';
    expect(deviceFor('mi-pc')?.url).toBe('https://pc.example');
  });

  it('strips a trailing slash so the joined path never doubles it', () => {
    process.env.DEVICE_CELULAR_URL = 'https://example.ngrok-free.dev/';
    process.env.DEVICE_CELULAR_TOKEN = 'token';
    expect(deviceFor('celular')?.url).toBe('https://example.ngrok-free.dev');
  });

  it('refuses a non-https endpoint', () => {
    process.env.DEVICE_CELULAR_URL = 'http://example.ngrok-free.dev';
    process.env.DEVICE_CELULAR_TOKEN = 'token';
    expect(deviceFor('celular')).toBeNull();
  });

  it('returns null when only one of the two variables is set', () => {
    process.env.DEVICE_CELULAR_URL = 'https://example.ngrok-free.dev';
    expect(deviceFor('celular')).toBeNull();
  });

  it('rejects a slug that could reach outside its own variables', () => {
    for (const slug of ['../celular', 'CELULAR', 'a', '', 'cel ular', 'cel/ular']) {
      expect(deviceFor(slug)).toBeNull();
    }
  });
});

describe('bearerFrom', () => {
  it('reads the token from an Authorization header', () => {
    const req = new Request('https://x/', { headers: { authorization: 'Bearer abc' } });
    expect(bearerFrom(req)).toBe('abc');
  });

  it('accepts the scheme case-insensitively', () => {
    const req = new Request('https://x/', { headers: { authorization: 'bearer abc' } });
    expect(bearerFrom(req)).toBe('abc');
  });

  it('returns null without a header, with another scheme, or with an empty token', () => {
    expect(bearerFrom(new Request('https://x/'))).toBeNull();
    expect(bearerFrom(new Request('https://x/', { headers: { authorization: 'Basic abc' } }))).toBeNull();
    expect(bearerFrom(new Request('https://x/', { headers: { authorization: 'Bearer   ' } }))).toBeNull();
  });
});
