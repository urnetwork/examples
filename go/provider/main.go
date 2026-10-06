// The Go provider example: a console app for Windows, macOS and Linux that
// runs a URnetwork provider for the developer's network and shows its status
// (PROVIDER_CONTRACT.md). It provides publicly with the scoped client
// credential that the developer's backend issued for this installation; the
// payout wallet is mapped by the backend and is only displayed here.
//
// Usage: provider [run] | --self-test | --version. All installation state is
// in the private directory named by URNETWORK_PROVIDER_STATE_DIR (state.go).
//
// Exit codes, for supervisors: 0 stopped on request, 78 configuration or
// credential problem (restarting does not help), 1 any other failure.
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

// Exits with the code of the command.
func main() {
	os.Exit(run(os.Args[1:]))
}

// Keeps the SDK's log files in logDir, which the SDK bounds (16 MiB files, the
// newest four kept at each start), instead of the system temp directory. The
// console keeps status lines and SDK errors; other SDK lines go to the files.
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
	switch {
	case len(args) == 1 && args[0] == "--self-test":
		if err := runSelfTest(); err != nil {
			fmt.Fprintf(os.Stderr, "provider self-test failed: %v\n", err)
			return exitFailure
		}
		fmt.Println("provider self-test passed")
		return exitStopped
	case len(args) == 1 && args[0] == "--version":
		fmt.Println(sdk.Version)
		return exitStopped
	case len(args) == 0 || (len(args) == 1 && args[0] == "run"):
	default:
		fmt.Fprintln(os.Stderr, "usage: provider [run] | --self-test | --version")
		return exitConfig
	}

	fmt.Println(consentDisclaimer)
	config, err := loadProviderConfig(os.Getenv("URNETWORK_PROVIDER_STATE_DIR"))
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return exitConfig
	}
	if err := configureSdkLogs(filepath.Join(config.stateDir, "logs")); err != nil {
		fmt.Fprintf(os.Stderr, "could not set the sdk log directory: %v\n", err)
		return exitFailure
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	session, err := newProviderSession(config)
	if err != nil {
		fmt.Fprintf(os.Stderr, "could not start the provider: %v\n", err)
		return exitFailure
	}
	defer session.Close()
	fmt.Printf("provider client %s, instance %s\n", config.clientId, config.instanceId)
	return session.Run(ctx)
}
