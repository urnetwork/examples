// The command line: node main.mjs [run] | --self-test | --version. A usage
// error exits with 78, like any configuration problem.

export const usage = "usage: node main.mjs [run] | --self-test | --version";

// The command of the arguments: "run", "self-test" or "version", or null for a
// usage error.
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
  return null;
}
