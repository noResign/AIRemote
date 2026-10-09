import net from 'node:net';

/**
 * Pick a port for the daemon we start.
 *
 * Defaulting to a fixed 4780 loses to the very machine this feature exists for:
 * the user often already has a daemon there (theirs, started by hand or by
 * systemd), and "start one" would then only ever fail with EADDRINUSE. The range
 * is the same one the discovery candidate table already scans (§2), so a port we
 * pick here is a port the next launch will find again.
 */
export const DEFAULT_PORT = 4780;
export const PORT_RANGE = 10;

/** True when nothing is listening on `host:port` right now. */
export function probeFree(port: number, host = '127.0.0.1'): Promise<boolean> {
  return new Promise((resolve) => {
    const server = net.createServer();
    server.once('error', () => resolve(false));
    server.once('listening', () => server.close(() => resolve(true)));
    try {
      server.listen(port, host);
    } catch {
      resolve(false);
    }
  });
}

export async function firstFreePort(options: {
  from?: number;
  count?: number;
  isFree?: (port: number) => Promise<boolean>;
} = {}): Promise<number | null> {
  const from = options.from ?? DEFAULT_PORT;
  const count = options.count ?? PORT_RANGE;
  const isFree = options.isFree ?? probeFree;
  for (let port = from; port < from + count; port += 1) {
    if (await isFree(port)) return port;
  }
  return null;
}
