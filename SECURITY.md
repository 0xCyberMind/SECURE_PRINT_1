# Security Policy

PrivPrint is under active development. This document explains which code is
currently covered by security fixes, how to report a vulnerability privately,
and the security boundaries described by the project. It is not a security
certification, an independent audit, or a guarantee that every deployment is
secure.

## Supported Versions

No versioned releases are currently listed for this repository. Security
reports are accepted for the latest code on the `main` branch. Other branches,
forks, and locally modified builds are not covered by a support commitment.

| Version or branch | Security fixes |
| --- | --- |
| Latest `main` | Reviewed and addressed as appropriate |
| Other branches or versions | Not guaranteed |

## Reporting a Vulnerability

Please do not disclose suspected vulnerabilities in public GitHub issues,
discussions, pull requests, or commit messages.

Use GitHub's private vulnerability reporting feature from the repository's
**Security** tab by selecting **Report a vulnerability**. This creates a
private report for the repository maintainers. If the private reporting option
is unavailable, contact the repository maintainers through a private GitHub
channel and ask for a secure way to submit the details. Do not post sensitive
information publicly while arranging a report.

When possible, include:

- A concise description of the issue and its potential impact.
- The affected component, branch or commit, and relevant configuration.
- Clear reproduction steps and a minimal, non-destructive proof of concept.
- Any mitigations or workarounds you have identified.

Please redact credentials, access tokens, private keys, personal information,
and real customer documents from reports, logs, screenshots, and proof-of-
concept files. Do not access, modify, or retain data that is not yours while
investigating a suspected issue.

The maintainers will review reports, assess impact, and coordinate any fix and
disclosure with the reporter as appropriate. Response and remediation times
depend on severity, reproducibility, and maintainer availability; no response
or remediation service level is guaranteed. Reporter credit can be included
with permission.

## Scope

Reports are welcome for security weaknesses in PrivPrint code and repository
configuration, including:

- The Android customer application.
- The Windows shop-station application and print agent.
- The backend API and its authentication, authorization, and document-job
  handling.
- Repository-provided deployment, build, and configuration files where an
  issue creates a security impact for PrivPrint users or operators.

Issues that exist only in a third-party service or a specific operator's
infrastructure should be reported to that service provider or operator.
Deployment-specific weaknesses may still be in scope when they are caused by
PrivPrint code or repository-provided configuration.

## Documented Security Design and Boundaries

The project documentation describes the following design:

- The Android client encrypts document content with AES-256-GCM using a
  12-byte nonce and a 128-bit authentication tag. It wraps the document key
  for a registered station using RSA-OAEP with SHA-256.
- The backend and configured object storage handle encrypted document content.
  The authorized Windows station decrypts a job in memory so it can be rendered
  and printed.
- Production client/API traffic is intended to use HTTPS and WSS. Windows
  station credentials are protected with Windows DPAPI for the signed-in user.

These are descriptions of the intended/documented application design, not
claims of independent verification. Encryption does not by itself secure
accounts, endpoints, deployment configuration, backups, or third-party
services. Application-level buffer clearing cannot guarantee erasure from the
runtime, operating system, storage provider, or printer. Printed pages are
outside the digital encryption boundary and require appropriate physical
handling.

Operators should keep secrets in an appropriate secret store, use separate
credentials for each environment, restrict access to production systems, and
review retention, backup, and cleanup behavior for their deployment. Never
commit populated environment files, credentials, access tokens, private keys,
or real customer documents.
