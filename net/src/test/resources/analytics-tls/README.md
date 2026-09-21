# Throwaway TLS test fixtures — NOT a secret, and never to be reused

These files exist so `RealAnalyticsUploadClientTlsTest` can drive a real `SSLServerSocket` on
loopback and prove the analytics uploader's TLS path actually executes (register R-1101). Before
them, the first real HTTPS request the client ever made would have been the first execution of
that code.

**What these are:** a self-signed certificate for `CN=localhost` and its private key, generated
with `keytool` on 2026-09-20 purely for this test.

**What they are not:** a credential. They protect nothing, they are published in a public
repository, and they are therefore compromised by construction. That is fine — a loopback test
server needs *a* certificate, and a deliberately worthless one is the honest choice.

**Rules:**

- Never reuse these for anything that is not this test. Not a staging endpoint, not a local dev
  proxy, not "just for a minute".
- Never add a real certificate or key to this repository, for any environment. The release
  keystore lives outside the repo and reaches CI through GitHub secrets; the analytics
  destination is a build-time configuration value, not a committed file.
- If a secret scanner flags the key here, that is the scanner working correctly. The answer is to
  confirm it is this fixture, not to weaken the scanner.

A mirrored PEM pair for the Python-side reference server lives at
`tools/analytics/tests/fixtures/tls/`, generated from the same throwaway pair and covered by
everything above.
