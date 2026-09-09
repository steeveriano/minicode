import { afterEach, describe, expect, it } from 'vitest';
import { ALLOWED_TOOLS, bearerFrom, deviceFor, isPrivateHost, rejectionReason, toolsFor } from './device.mts';

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

  it('refuses plain http to a public host', () => {
    // A device token in the clear across the open internet. The one case this must never allow.
    process.env.DEVICE_CELULAR_URL = 'http://example.ngrok-free.dev';
    process.env.DEVICE_CELULAR_TOKEN = 'token';
    expect(deviceFor('celular')).toBeNull();
  });

  it('admits plain http to a private address, which is what LAN mode needs', () => {
    process.env.DEVICE_CELULAR_URL = 'http://192.168.110.158:8080';
    process.env.DEVICE_CELULAR_TOKEN = 'token';
    expect(deviceFor('celular')?.url).toBe('http://192.168.110.158:8080');
  });

  it('refuses a scheme that is neither http nor https', () => {
    process.env.DEVICE_CELULAR_URL = 'file:///etc/passwd';
    process.env.DEVICE_CELULAR_TOKEN = 'token';
    expect(deviceFor('celular')).toBeNull();
  });

  it('refuses a url it cannot parse', () => {
    process.env.DEVICE_CELULAR_URL = 'not a url';
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

describe('toolsFor', () => {
  it('read is the browsing set and nothing else', () => {
    expect(toolsFor('read')).toEqual(ALLOWED_TOOLS);
  });

  it('write adds reversible changes but never deletion', () => {
    const tools = toolsFor('write');
    expect(tools.has('android_move_file')).toBe(true);
    expect(tools.has('android_quarantine_files')).toBe(true);
    expect(tools.has('android_restore_quarantine_batch')).toBe(true);
    expect(tools.has('android_delete_file')).toBe(false);
    expect(tools.has('android_purge_quarantine_batch')).toBe(false);
  });

  it('full is the only level that can destroy', () => {
    const tools = toolsFor('full');
    expect(tools.has('android_delete_file')).toBe(true);
    expect(tools.has('android_purge_quarantine_batch')).toBe(true);
  });

  it('no level admits driving the screen', () => {
    for (const level of ['read', 'write', 'full'] as const) {
      for (const name of ['android_tap', 'android_swipe', 'android_open_app', 'android_set_clipboard']) {
        expect(toolsFor(level).has(name)).toBe(false);
      }
    }
  });
});

describe('rejectionReason at each level', () => {
  it('defaults to read when no level is given', () => {
    expect(rejectionReason(call('android_move_file'))).toContain('android_move_file');
  });

  it('read refuses a move', () => {
    expect(rejectionReason(call('android_move_file'), 'read')).not.toBeNull();
  });

  it('write allows a move and refuses a delete', () => {
    expect(rejectionReason(call('android_move_file'), 'write')).toBeNull();
    expect(rejectionReason(call('android_delete_file'), 'write')).not.toBeNull();
  });

  it('full allows a delete', () => {
    expect(rejectionReason(call('android_delete_file'), 'full')).toBeNull();
  });

  it('a batch is judged at the same level as the rest', () => {
    const batch = [call('android_list_files'), call('android_delete_file')];
    expect(rejectionReason(batch, 'write')).toContain('android_delete_file');
    expect(rejectionReason(batch, 'full')).toBeNull();
  });
});

describe('isPrivateHost', () => {
  it('accepts the three RFC 1918 ranges', () => {
    expect(isPrivateHost('10.0.0.1')).toBe(true);
    expect(isPrivateHost('192.168.110.158')).toBe(true);
    expect(isPrivateHost('172.16.0.1')).toBe(true);
    expect(isPrivateHost('172.31.255.254')).toBe(true);
  });

  it('rejects the addresses just outside 172.16.0.0/12', () => {
    // The range everyone gets wrong: 172.15 and 172.32 are public.
    expect(isPrivateHost('172.15.0.1')).toBe(false);
    expect(isPrivateHost('172.32.0.1')).toBe(false);
  });

  it('accepts loopback and link-local', () => {
    expect(isPrivateHost('127.0.0.1')).toBe(true);
    expect(isPrivateHost('localhost')).toBe(true);
    expect(isPrivateHost('::1')).toBe(true);
    expect(isPrivateHost('169.254.1.1')).toBe(true);
    expect(isPrivateHost('phone.local')).toBe(true);
  });

  it('rejects public addresses and anything not an address', () => {
    expect(isPrivateHost('8.8.8.8')).toBe(false);
    expect(isPrivateHost('example.com')).toBe(false);
    expect(isPrivateHost('192.168.1')).toBe(false);
    expect(isPrivateHost('999.1.1.1')).toBe(false);
  });

  it('is not fooled by a hostname that merely contains a private address', () => {
    // `192.168.1.1.evil.com` resolves wherever the attacker wants.
    expect(isPrivateHost('192.168.1.1.evil.com')).toBe(false);
  });
});
