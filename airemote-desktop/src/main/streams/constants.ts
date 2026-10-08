/**
 * How long a batched group of delta frames may sit before being flushed to the
 * renderer. ~One frame at 60Hz: often enough to look live, rare enough that a
 * token storm does not become a storm of IPC messages.
 */
export const LINE_FLUSH_MS = 16;
