import { describe, expect, it } from 'vitest';
import { formatListen, parseAddress } from './address';

describe('parseAddress', () => {
  it('defaults the scheme and port', () => {
    expect(parseAddress('192.168.1.9')).toEqual({ host: '192.168.1.9', port: 4780, tls: false });
    expect(parseAddress('127.0.0.1:4790')).toEqual({ host: '127.0.0.1', port: 4790, tls: false });
    expect(parseAddress('https://box.lan:8443')).toEqual({ host: 'box.lan', port: 8443, tls: true });
  });

  it('rejects empty and out-of-range input', () => {
    expect(parseAddress('')).toBeNull();
    expect(parseAddress('   ')).toBeNull();
    expect(parseAddress('http://host:99999')).toBeNull();
  });

  it('formats IPv6 with brackets', () => {
    expect(formatListen('::1', 4780)).toBe('[::1]:4780');
    expect(formatListen('127.0.0.1', 4780)).toBe('127.0.0.1:4780');
  });
});
