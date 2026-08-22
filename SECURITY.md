# Security Policy

## Supported Versions

This project is under active development.

Security fixes will generally target the latest released version.

| Version | Supported |
|---|---|
| Latest release | Yes |
| Older releases | Best effort |
| Development snapshots | No guarantee |

---

## Reporting a Vulnerability

Please do **not** open a public GitHub issue for a security vulnerability that could expose users, credentials, devices, or server infrastructure.

Preferred reporting method:

1. Use GitHub's private vulnerability reporting feature if enabled for this repository.
2. If private vulnerability reporting is unavailable, contact the maintainer through a private channel listed in the repository profile or project documentation.

Please include:

- a concise description of the issue,
- affected version or commit,
- reproduction steps,
- expected impact,
- whether exploitation requires a valid device credential,
- whether the issue affects the Android client, server API, build/release process, or documentation,
- any suggested mitigation if known.

Do not include real credentials, private keys, passwords, access tokens, or unrelated personal data in the report.

---

## What Counts as a Security Issue

Examples include:

- authentication bypass,
- authorization bypass,
- device impersonation,
- revocation bypass,
- arbitrary server command execution,
- exposure of server secrets,
- leakage of device private keys,
- insecure local storage of credentials,
- replay attacks with meaningful impact,
- remote code execution,
- unsafe WebView or arbitrary script execution,
- insecure APK update/signing behavior,
- CI workflows that expose production secrets,
- dependency or supply-chain vulnerabilities,
- unintended access to tasks, notes, chats, projects, or agent data,
- privilege escalation from low-risk device capabilities to administrative capabilities.

---

## Architecture Assumptions

The Android client is intentionally open source.

The project does **not** treat source-code secrecy, API obscurity, hidden endpoints, or unpublished request formats as security controls.

The expected security boundary is:

- enrolled device identity,
- cryptographic authentication,
- server-side authorization,
- narrow capabilities,
- revocation,
- secure release signing.

Reports demonstrating that an unauthenticated or low-privilege client can cross these boundaries are especially important.

---

## Out of Scope

The following are generally not considered vulnerabilities by themselves:

- the API hostname being discoverable,
- API routes being visible in the source,
- protocol formats being visible,
- a user compiling a modified copy of the open-source client,
- denial of functionality on unsupported Android devices,
- UI bugs without a security impact,
- issues that require full control of the server operating system first,
- social engineering without a technical vulnerability.

A modified client gaining **additional server authority** beyond its enrolled capabilities is in scope.

---

## Responsible Testing

Please:

- test only against systems and accounts you are authorized to use,
- avoid destructive actions,
- avoid accessing other users' data,
- avoid persistent denial-of-service testing,
- avoid publishing exploit details before the issue has been assessed.

---

## Disclosure

The project aims to:

1. acknowledge valid reports,
2. assess severity,
3. develop and test a fix,
4. publish a patched release,
5. disclose relevant details after users have a reasonable opportunity to update.

Exact timelines may vary with project maturity and issue severity.

---

## Release Authenticity

Official Android releases should be distributed through the project's documented release channel and signed with the project's official Android signing key.

Users should avoid installing APKs from untrusted mirrors or third-party sources unless they intentionally choose to run an unofficial build.

When available, verify:

- release version,
- checksum,
- signing certificate fingerprint,
- source commit.

