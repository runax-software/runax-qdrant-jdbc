# Security Policy

## Reporting a Vulnerability

If you discover a security vulnerability in the Runax JDBC Driver for Qdrant, please report it responsibly.

**Do not open a public issue.**

Instead, [open a private security advisory](https://github.com/runax-software/runax-qdrant-jdbc/security/advisories/new) on GitHub.

Please include:

- Description of the vulnerability
- Steps to reproduce
- Potential impact
- Suggested fix (if any)

## Response

We will acknowledge your report within 48 hours and aim to provide a fix or mitigation within 7 days for critical issues.

## Supported Versions

| Version | Supported |
| ------- | --------- |
| latest  | Yes       |

## Scope

This policy covers the code in the driver. The driver handles Qdrant API keys and embedding-provider API keys, so keys leaking through logs, exception messages, or connection URLs are in scope. Third-party dependencies are bundled into the driver jar.
