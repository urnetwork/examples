// The Go embed example, the reference embed app (EMBED_CONTRACT.md): a
// console app for Windows, macOS and Linux that embeds the URnetwork SDK in
// one installation of your app. It obtains the installation's scoped client
// JWT from your backend, starts a local Device with it, connects to the best
// available location and shows the status and the installation's data caps.
// The Device carries only the app's own traffic, not the device's: the
// Sockets and Messages examples continue from here with the same state.
//
// Usage: embed [run] | --self-test | --version. The installation state is in
// the private directory named by URNETWORK_EMBED_STATE_DIR (state.go). The
// client JWT comes from a token server (URNETWORK_TOKEN_SERVER_URL and
// URNETWORK_DEMO_SESSION, token.go) or from client.jwt in that directory.
//
// Exit codes, for supervisors: 0 stopped on request, 78 a configuration or
// credential problem that a restart does not fix, 1 any other failure.
package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"

	sdk "github.com/urnetwork/sdk/v2026"
)

const (
	exitStopped = 0
	exitFailure = 1
	// sysexits EX_CONFIG
	exitConfig = 78
)

const usage = "usage: embed [run] | --self-test | --version"

// Exits with the code of the command.
func main() {
	os.Exit(run(os.Args[1:]))
}

// The command of the arguments: "run", "--self-test" or "--version". Any
// other arguments are a usage error (exit 78).
func commandOf(args []string) (string, bool) {
	switch {
	case len(args) == 0 || (len(args) == 1 && args[0] == "run"):
		return "run", true
	case len(args) == 1 && (args[0] == "--self-test" || args[0] == "--version"):
		return args[0], true
	default:
		return "", false
	}
}

// Keeps the SDK's log files in logDir, which the SDK bounds, instead of the
// system temp directory. The console keeps status lines and SDK errors;
// other SDK lines go to the files.
func configureSdkLogs(logDir string) error {
	if err := os.MkdirAll(logDir, 0o700); err != nil {
		return err
	}
	if err := flag.Set("alsologtostderr", "false"); err != nil {
		return err
	}
	if err := flag.Set("stderrthreshold", "ERROR"); err != nil {
		return err
	}
	return sdk.SetLogDir(logDir)
}

// Runs one command and returns the exit code.
func run(args []string) int {
	command, ok := commandOf(args)
	switch {
	case !ok:
		fmt.Fprintln(os.Stderr, usage)
		return exitConfig
	case command == "--self-test":
		if err := runSelfTest(); err != nil {
			fmt.Fprintf(os.Stderr, "embed self-test failed: %v\n", err)
			return exitFailure
		}
		fmt.Println("embed self-test passed")
		return exitStopped
	case command == "--version":
		fmt.Println(sdk.Version)
		return exitStopped
	}

	// 1. the installation state, with instance-id created on first run
	config, err := loadEmbedConfig(os.Getenv)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return exitConfig
	}
	// 2. the client JWT
	credential, err := obtainClientJwt(config, newHttpClient())
	if err != nil {
		fmt.Fprintln(os.Stderr, oneLine(err.Error()))
		return startupExitCode(err)
	}
	// 3. the SDK's logs in the state directory
	if err := configureSdkLogs(filepath.Join(config.stateDir, logsDirName)); err != nil {
		fmt.Fprintf(os.Stderr, "could not set the sdk log directory: %v\n", err)
		return exitFailure
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	fmt.Printf("embed client %s, installation %s\n", credential.clientId, config.instanceId)
	// 4 to 7. the manager, the device, its listeners and its connect location
	session, err := newEmbedSession(config, credential)
	if err != nil {
		fmt.Fprintf(os.Stderr, "could not start the embedded device: %v\n", err)
		return exitFailure
	}
	// 10. close the subscriptions, the device, then the manager
	defer session.Close()
	// 8 and 9. the caps and the status
	return session.Run(ctx)
}
