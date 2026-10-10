export interface ParsedAddress {
  host: string;
  port: number;
  tls: boolean;
}

/** Accept `host`, `host:port`, `http(s)://host[:port]`; default to loopback:4780. */
export function parseAddress(raw: string, defaultPort = 4780): ParsedAddress | null {
  const trimmed = raw.trim();
  if (!trimmed) return null;
  const withScheme = /^https?:\/\//i.test(trimmed) ? trimmed : `http://${trimmed}`;
  try {
    const url = new URL(withScheme);
    if (!url.hostname) return null;
    const port = url.port ? Number.parseInt(url.port, 10) : defaultPort;
    if (!Number.isInteger(port) || port <= 0 || port > 65535) return null;
    return { host: url.hostname, port, tls: url.protocol === 'https:' };
  } catch {
    return null;
  }
}

/** `host:port` for display and for matching a discovered daemon. */
export function formatListen(host: string, port: number): string {
  const bracketed = host.includes(':') ? `[${host}]` : host;
  return `${bracketed}:${port}`;
}
