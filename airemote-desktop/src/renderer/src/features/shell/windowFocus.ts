/**
 * The session a window was opened for, pulled at most once per renderer process.
 *
 * Two things make a plain `useEffect` wrong here:
 *
 * - `focusSession()` is **destructive** on the main side — it clears the pending
 *   id — so a second call comes back empty;
 * - React StrictMode runs effects twice in development, and the first pass's
 *   result is dropped when its cleanup runs.
 *
 * Together those silently lost the request: pass one consumed the id and threw
 * the answer away, pass two asked again and got `null`, so a second window opened
 * with nothing in it. Memoizing the promise makes the second pass harmless, and
 * `claim()` keeps it from acting twice on the same session.
 */
export function createWindowFocus(pull: () => Promise<string | null>): {
  session(): Promise<string | null>;
  claim(): boolean;
} {
  let promise: Promise<string | null> | null = null;
  let claimed = false;
  return {
    session() {
      promise ??= pull();
      return promise;
    },
    claim() {
      if (claimed) return false;
      claimed = true;
      return true;
    },
  };
}
