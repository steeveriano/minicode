/**
 * Parsing for the device's `disk_usage` output.
 *
 * Its own module so the capture script and its tests share one implementation: the format is the
 * seam between two codebases in this repository, and a second copy would be the thing that drifts.
 */

/**
 * Strips the device's untrusted-content banner.
 *
 * Every tool returning device-derived data prefixes it with a warning aimed at a language model.
 * It is not part of the payload, and leaving it in breaks both the JSON parses and the line scan.
 */
export function payload(text) {
  return text
    .split('\n')
    .filter((line) => !line.startsWith('CAUTION:') && !line.startsWith('WARNING:'))
    .join('\n')
    .trim();
}

const LINE = /^(\s*)(.*?) — (\d+) bytes, (\d+) file\(s\)\s*$/;

/**
 * Turns the indented usage listing into a tree.
 *
 * The tool renders one line per directory, two spaces of indent per level:
 *   `  Camera — 25139050075 bytes, 4897 file(s)`
 * Each line already carries a complete location-relative path, so the indent is used only to decide
 * what a node hangs from. The first line is the location root, whose path the tool prints as `/`.
 *
 * @returns the root node, or null when the text contains no usage line at all.
 */
export function parseUsage(text) {
  const root = { path: '', totalBytes: 0, fileCount: 0, children: [] };
  const stack = [{ depth: -1, node: root }];
  let seenRoot = false;

  for (const line of payload(text).split('\n')) {
    const m = LINE.exec(line);
    if (!m) continue;
    const depth = m[1].length / 2;
    if (!seenRoot) {
      root.totalBytes = Number(m[3]);
      root.fileCount = Number(m[4]);
      seenRoot = true;
      continue;
    }
    const node = {
      path: m[2],
      totalBytes: Number(m[3]),
      fileCount: Number(m[4]),
      children: [],
    };
    while (stack.length > 1 && stack[stack.length - 1].depth >= depth) stack.pop();
    stack[stack.length - 1].node.children.push(node);
    stack.push({ depth, node });
  }
  return seenRoot ? root : null;
}
