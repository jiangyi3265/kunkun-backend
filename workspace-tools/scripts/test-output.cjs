// Preserve standalone legacy paths, but isolate every unified acceptance run.
const fs = require('node:fs'), path = require('node:path');
const root = path.resolve(__dirname, '..');
function dir(legacy = '') {
  const result = process.env.QA_OUTPUT_DIR
    ? path.resolve(process.env.QA_OUTPUT_DIR)
    : path.join(root, 'test-results', legacy);
  fs.mkdirSync(result, { recursive: true });
  return result;
}
function file(legacy) {
  return process.env.QA_OUTPUT_DIR
    ? path.join(dir(), path.basename(legacy))
    : path.join(dir(path.dirname(legacy)), path.basename(legacy));
}
module.exports = { dir, file };
