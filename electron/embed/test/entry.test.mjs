// The app's entry points start from their top-level code, without an
// import.meta.main check. Electron loads main.mjs as its main script, where
// Electron 44 reads import.meta.main as false, so behind such a check the app
// would never start. The tests start fake-companion.mjs as a child process,
// which behind such a check would do nothing on Node 24.0 and 24.1, before
// Node defined import.meta.main.

import {test} from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";

// The code of an app file without its comment lines.
function code(name) {
  const source = readFileSync(new URL(`../${name}`, import.meta.url), "utf8");
  return source.split("\n").filter(line => !line.trimStart().startsWith("//")).join("\n");
}

test("the entry points start at load without an import.meta.main check", () => {
  for (const name of ["main.mjs", "test/fake-companion.mjs"]) {
    assert.doesNotMatch(code(name), /import\.meta\.main/, name);
  }
  // main.mjs starts the app from its top level
  assert.match(code("main.mjs"), /^if \(app\.requestSingleInstanceLock\(\)\) \{\n {2}runApp\(\);\n\} else \{/m);
  // package.json names main.mjs as Electron's main script
  assert.equal(JSON.parse(readFileSync(new URL("../package.json", import.meta.url), "utf8")).main, "main.mjs");
});
