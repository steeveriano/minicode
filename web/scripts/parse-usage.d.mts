/**
 * Types for the shared usage parser.
 *
 * The implementation stays in `parse-usage.mjs` because the capture script is plain Node and cannot
 * import TypeScript. Declaring it here lets the browser code use the same module rather than grow a
 * second copy — which, per this project's own rule, is the copy that would drift.
 */

/** Strips the device's untrusted-content banner from a tool response. */
export function payload(text: string): string;

/** One directory in a usage walk. Paths are relative to the location root. */
export type UsageTreeNode = {
  path: string;
  totalBytes: number;
  fileCount: number;
  children: UsageTreeNode[];
};

/** Turns the indented usage listing into a tree, or null when it contains no usage line. */
export function parseUsage(text: string): UsageTreeNode | null;
