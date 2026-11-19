// Service-owned: JSON parsing that keeps large integers exact. Datadog's trace-by-id API returns
// ids and nanosecond timestamps as JSON numbers above 2^53 (API.md §4), which `JSON.parse` would
// round. Integer literals with more than 15 digits become strings before parsing.

const SAFE_DIGITS = 15;

/**
 * Parses JSON like `JSON.parse`, but every integer literal with more than 15 digits is returned
 * as a decimal string (e.g. `{"spanID": 9876543210987654321}` → `{ spanID: "9876543210987654321" }`).
 * Numbers inside strings are never touched.
 */
export function parseJsonPreservingBigInts(text: string): unknown {
  let out = "";
  let last = 0;
  let i = 0;
  const n = text.length;
  while (i < n) {
    const c = text.charCodeAt(i);
    if (c === 0x22 /* " */) {
      i++;
      while (i < n) {
        const d = text.charCodeAt(i);
        if (d === 0x5c /* \ */) i += 2;
        else if (d === 0x22) {
          i++;
          break;
        } else i++;
      }
      continue;
    }
    if (c === 0x2d /* - */ || (c >= 0x30 && c <= 0x39)) {
      const start = i;
      if (c === 0x2d) i++;
      while (i < n && text.charCodeAt(i) >= 0x30 && text.charCodeAt(i) <= 0x39) i++;
      const intEnd = i;
      while (i < n && /[0-9eE.+-]/.test(text.charAt(i))) i++;
      const digits = intEnd - start - (c === 0x2d ? 1 : 0);
      if (i === intEnd && digits > SAFE_DIGITS) {
        out += text.slice(last, start) + `"${text.slice(start, intEnd)}"`;
        last = intEnd;
      }
      continue;
    }
    i++;
  }
  return JSON.parse(out + text.slice(last)) as unknown;
}
