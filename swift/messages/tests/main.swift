import Foundation

do { try codecSelfTest() } catch {
  fputs("codec self-test failed: \(error)\n", stderr)
  exit(1)
}
