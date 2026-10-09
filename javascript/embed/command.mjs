// The command line: node main.mjs [run] | --self-test | --licenses |
// --version. A usage error exits with 78, like any configuration problem.

export const usage = "usage: node main.mjs [run] | --self-test | --licenses | --version";

// The command of the arguments: "run", "self-test", "licenses" or "version",
// or null for a usage error.
export function parseCommand(args) {
  if (args.length === 0 || (args.length === 1 && args[0] === "run")) {
    return "run";
  }
  if (args.length === 1 && args[0] === "--self-test") {
    return "self-test";
  }
  if (args.length === 1 && args[0] === "--version") {
    return "version";
  }
  if (args.length === 1 && args[0] === "--licenses") {
    return "licenses";
  }
  return null;
}
