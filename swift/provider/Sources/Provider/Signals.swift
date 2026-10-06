// Stop requests: SIGINT (Ctrl-C) and SIGTERM on POSIX, Ctrl-C and Ctrl-Break on
// Windows. Each becomes a stop request in the inbox, and the status loop then
// stops providing and exits with code 0.

import Dispatch
import Foundation

#if canImport(Darwin)
  import Darwin
#elseif canImport(Glibc)
  import Glibc
#elseif canImport(Musl)
  import Musl
#elseif canImport(CRT)
  import CRT
#endif
#if os(Windows)
  import WinSDK
#endif

/// Forwards stop signals to an inbox for as long as it lives.
final class StopSignals {
  #if !os(Windows)
    private var sources: [DispatchSourceSignal] = []
  #endif

  /// Starts forwarding stop signals to inbox.
  init(inbox: ProviderInbox) {
    #if os(Windows)
      consoleStopInbox = inbox
      // a later handler runs first, so this one also comes before any the SDK set
      SetConsoleCtrlHandler(
        { controlType in
          if controlType != DWORD(CTRL_C_EVENT) && controlType != DWORD(CTRL_BREAK_EVENT) {
            return false
          }
          consoleStopInbox?.requestStop()
          return true
        }, true)
    #else
      for signalNumber in [SIGINT, SIGTERM] {
        // the dispatch source receives the signal instead of its default action
        signal(signalNumber, SIG_IGN)
        let source = DispatchSource.makeSignalSource(signal: signalNumber, queue: .global())
        source.setEventHandler {
          inbox.requestStop()
        }
        source.resume()
        sources.append(source)
      }
    #endif
  }
}

#if os(Windows)
  /// The inbox of the console control handler, which is a C function and cannot
  /// capture it.
  private var consoleStopInbox: ProviderInbox?
#endif
