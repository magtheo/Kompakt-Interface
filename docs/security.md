# Security Architecture

## Purpose

This document defines the security model for the Mudita Kompakt personal interface and its server-backed architecture.

The central security assumption is:

> The phone may eventually be lost, stolen, modified, or compromised.

The system must therefore be designed so that compromise of the Kompakt does **not** imply compromise of the server, infrastructure, credentials, or other trusted devices.

The Android client is intended to be open source. The security model must remain valid even if an attacker knows:

- the complete APK source code,
- the API schema,
- the server hostname,
- the authentication protocol,
- the client-side permission model,
- the cryptographic algorithms,
- and the build process.

Security must come from **cryptographic identity, server-side authorization, capability boundaries, revocation, and limited device privileges** — not from secrecy of the client implementation.

---

## Security Goals

The system should ensure that:

1. A compromised phone cannot automatically compromise the server.
2. A stolen phone can be revoked quickly.
3. The APK contains no embedded high-value secrets.
4. A modified or unofficial APK does not gain additional authority.
5. Sensitive or destructive server actions require stronger authorization than the Kompakt can provide.
6. Server-side authorization is authoritative.
7. The app requests only the Android permissions it actually needs.
8. Local device data is minimized.
9. Important secrets remain on the server or more trusted devices.
10. Public source code and public API documentation do not weaken the trust model.
11. The official APK can be distinguished from unofficial builds through Android signing and release provenance.
12. Public contribution and CI workflows cannot expose production secrets or signing material.

---

## Trust Model

### Trusted

The system may trust:

- the server-side authorization layer,
- server-held private credentials,
- server policy configuration,
- an explicitly enrolled device public key,
- the official APK signing key,
- higher-trust administrative devices used for privileged operations.

### Partially trusted

The Kompakt is a **low-trust client**.

It may be trusted to possess a revocable device credential, but it must not be trusted to:

- enforce security policy,
- determine which operations are allowed,
- protect server secrets,
- prove that an unmodified APK is running,
- or authorize high-impact administrative actions by itself.

### Untrusted

Treat the following as untrusted:

- the network,
- public Wi-Fi,
- user-submitted chat text,
- agent-generated text,
- web content retrieved by agents,
- modified APKs,
- cloned API clients,
- arbitrary HTTP requests,
- public GitHub contributors,
- pull-request CI code,
- and any client-side value claiming that an operation is permitted.

---

## Threat Model

### Threat 1 — App-only compromise

An attacker gains code execution inside the APK process but does not escape Android's application sandbox.

Potential exposure:

- app-local cache,
- recent chat/task/note data,
- outgoing requests,
- API responses,
- microphone access if granted,
- the app's device-authentication capability,
- queued offline commands.

Expected containment:

- no access to arbitrary server filesystem,
- no root shell,
- no server secrets,
- no unrelated Android app data,
- no privileged infrastructure operations.

---

### Threat 2 — Full phone compromise

An attacker gains system-level or root-level control of the Kompakt.

Assume the attacker may be able to observe:

- what is displayed,
- what is typed,
- what is dictated,
- notifications,
- SMS/call metadata depending on system privileges,
- microphone input,
- app storage,
- network requests before encryption and after decryption,
- device credentials while the device remains authorized.

The system must still ensure:

> Full control of the Kompakt does not grant unrestricted control of the server.

---

### Threat 3 — Lost or stolen phone

An attacker physically obtains the device.

Response:

1. revoke the device identity server-side,
2. terminate active sessions,
3. reject future signed requests from that device,
4. optionally rotate any session-derived secrets,
5. reset or replace the phone,
6. enroll a new device.

The phone should remain replaceable because durable state lives on the server.

---

### Threat 4 — Malicious or modified APK

Because the client is open source, anyone can compile a modified version.

The security design must assume:

> Any software capable of speaking the API protocol can submit requests.

Therefore:

- the server must authenticate the device,
- the server must authorize every operation,
- the server must validate every request,
- the client must never be trusted merely because it presents expected UI behavior.

A modified APK must not gain capabilities beyond those granted to its device identity.

---

### Threat 5 — Malicious network

Assume attackers may control or observe:

- public Wi-Fi,
- local network routing,
- DNS infrastructure,
- intermediate network devices.

The app should use:

- HTTPS,
- modern TLS,
- hostname verification,
- secure server certificates,
- no cleartext HTTP fallback.

Do not invent custom encryption to replace TLS.

---

### Threat 6 — Server/API abuse

A legitimate Kompakt credential may be used to:

- spam expensive agent jobs,
- attempt malformed requests,
- send oversized inputs,
- repeat commands,
- probe unsupported operations.

The server should enforce:

- capability checks,
- request validation,
- request-size limits,
- rate limits,
- replay protection where relevant,
- idempotency for repeatable actions,
- agent-job quotas if needed,
- audit logging for material operations.

---

### Threat 7 — Supply-chain or CI compromise

A public repository introduces build-system risk.

Potential vectors include:

- malicious pull requests,
- compromised dependencies,
- compromised GitHub Actions,
- stolen release signing credentials,
- untrusted workflow execution.

Release credentials and production credentials must never be available to arbitrary pull-request code.

---

## Open-Source Security Principle

The client is intended to be open source.

This is compatible with the security model.

The repository may safely expose:

- API endpoint formats,
- domain models,
- client source code,
- cryptographic algorithms,
- protocol definitions,
- server public keys or certificate metadata,
- build instructions.

The repository must not contain:

- device private keys,
- server private keys,
- production API credentials,
- SSH credentials,
- database passwords,
- recovery codes,
- release signing private keys,
- secrets copied into test fixtures,
- private `.env` files,
- real access tokens.

The core rule is:

> Knowledge of the protocol must not imply authority to use the protocol.

---

## Device Enrollment

### Initial enrollment

The preferred design is:

1. Install the APK.
2. App generates a device key pair.
3. Private key is stored through Android Keystore.
4. Public key is shown or transmitted as part of an enrollment request.
5. A more trusted device or server-side administrative flow approves the enrollment.
6. Server creates a device record.
7. Server associates that device with a capability set.

Example server record:

```text
device_id: kompakt-01
public_key: ...
status: active

capabilities:
  today.read
  inbox.read
  chat.read
  chat.write
  task.read
  task.create
  task.update
  note.read
  note.create
  agent.read
  agent.run.low_risk
```

---

## Device Identity and Key Storage

Use Android Keystore for device credentials.

Preferred characteristics:

- asymmetric key pair,
- private key not normally exportable,
- hardware-backed storage where supported,
- server stores only the public key,
- device signs authentication challenges or requests.

Do not store a master private key in:

- SharedPreferences,
- plain files,
- application resources,
- source code,
- repository secrets intended for runtime use.

Hardware-backed storage on Mudita Kompakt must be tested rather than assumed.

---

## Authentication

Authentication should prove:

> This request comes from an enrolled device identity.

Possible approaches include:

- signed requests,
- short-lived server-issued session tokens established from a device key,
- challenge-response authentication.

The protocol should prevent trivial replay where appropriate.

Authentication is separate from authorization.

A valid device must still be denied operations outside its capability set.

---

## Authorization

Authorization must be enforced on the server.

The phone must never determine whether an operation is permitted.

### Example permission boundary

The Kompakt may be allowed to:

- read Today,
- read Inbox,
- read project summaries,
- create notes,
- create tasks,
- update task status,
- send chat messages,
- start low-risk agent jobs,
- inspect agent status,
- approve specifically defined low-risk operations.

The Kompakt should not be allowed to:

- read arbitrary server files,
- obtain server secrets,
- execute arbitrary shell commands,
- modify SSH configuration,
- rotate credentials,
- alter authentication policy,
- deploy to critical production systems,
- delete repositories,
- delete backups,
- perform financial operations,
- administer users or identities.

---

## Action Risk Classes

Define explicit operation classes.

### Class 1 — Read

Examples:

- read task,
- read note,
- read chat,
- read agent result,
- read project status.

Kompakt:

**Allowed**

---

### Class 2 — Low-risk write

Examples:

- create note,
- create task,
- complete task,
- send chat message,
- ask an agent to research something.

Kompakt:

**Allowed**

---

### Class 3 — Reversible operational action

Examples:

- run tests,
- create a draft,
- start a research job,
- generate a patch,
- open a non-destructive workflow.

Kompakt:

**Generally allowed, possibly with explicit confirmation**

---

### Class 4 — Material change

Examples:

- merge PR,
- deploy,
- modify important production configuration,
- publish externally,
- send irreversible external communication.

Kompakt:

**Restricted or requires stronger authorization**

---

### Class 5 — Security / administrative

Examples:

- reveal secrets,
- change credentials,
- change firewall rules,
- alter SSH configuration,
- modify users,
- change authorization policy.

Kompakt:

**Not allowed**

---

### Class 6 — Destructive

Examples:

- delete repositories,
- wipe databases,
- delete backups,
- destroy production state.

Kompakt:

**Not allowed**

---

## API Design Rules

The API should expose high-level capabilities, not raw system control.

Good:

```text
POST /v1/tasks
POST /v1/agent-runs
POST /v1/agent-runs/{id}/actions
```

Bad:

```text
POST /v1/shell
POST /v1/exec
POST /v1/raw-sql
```

Do not expose an arbitrary command execution endpoint to the Kompakt.

---

## Server-Supplied Actions

The server may return a set of allowed UI actions.

Example:

```json
{
  "actions": [
    {
      "id": "complete",
      "label": "Complete"
    },
    {
      "id": "ask_agent",
      "label": "Ask agent"
    }
  ]
}
```

The client may render these actions, but:

- action identifiers must come from a known supported set,
- the server must still authorize the requested action,
- the response must not act as arbitrary executable UI logic.

Do not build a generic remote-code protocol.

---

## No Arbitrary Web Rendering

The APK should not become an embedded browser.

Agents may browse the web on the server, but the phone should receive:

- plain text,
- sanitized structured text,
- small lists,
- headings,
- status blocks,
- actions,
- controlled links.

Avoid rendering:

- arbitrary HTML,
- arbitrary JavaScript,
- untrusted WebViews,
- remotely supplied scripts,
- complex embedded web content.

Preferred flow:

```text
web content
    ↓
server/agent
    ↓
parse + summarize + sanitize
    ↓
structured response
    ↓
Kompakt
```

---

## Android Permissions

Request the minimum permissions required.

Likely baseline:

- Internet
- Notifications
- Microphone only if voice capture is implemented in-app

Avoid requesting unless a concrete feature requires them:

- contacts,
- SMS,
- call logs,
- location,
- camera,
- Bluetooth,
- broad file access.

A small permission set reduces the impact of an app-only compromise.

---

## Local Data Policy

The Kompakt should store as little durable information as practical.

Possible cached data:

- today's tasks,
- today's events,
- recent inbox items,
- recent chat snippets,
- recent agent summaries,
- recent notes,
- pending offline actions,
- preferences.

Avoid storing:

- complete repositories,
- full server archives,
- credentials,
- password vaults,
- long-lived secrets,
- private recovery material.

---

## Cache Retention

Initial suggested policy:

```text
Today cache:
24–72 hours

Inbox:
limited recent items

Chat:
recently opened threads only

Agent results:
summaries or recent result excerpts

Notes:
recently accessed notes

Voice recordings:
delete immediately after successful processing

Offline queue:
delete after confirmed sync
```

Exact retention values remain configurable.

---

## Sensitive Input

Even when nothing sensitive is stored, a fully compromised phone may observe what the user enters.

Avoid using the Kompakt for:

- master passwords,
- seed phrases,
- recovery codes,
- highly privileged administrative secrets,
- confidential one-time credentials.

A compromised endpoint can observe data before TLS encryption.

---

## Backups

The app should not automatically back up sensitive local state unless intentionally designed.

Consider disabling Android application backup by default.

If backup is later enabled, explicitly define which data is safe to back up.

---

## Logging

Logs must not contain:

- auth tokens,
- private keys,
- raw credentials,
- sensitive chat bodies unless explicitly required,
- private voice recordings,
- server secrets.

Production logs should prefer:

- request IDs,
- device IDs,
- action names,
- status codes,
- timestamps,
- audit metadata.

---

## Voice Security

If voice transcription runs server-side:

1. record only when initiated,
2. use secure transport,
3. avoid long-term raw-audio storage by default,
4. delete temporary audio after successful transcription,
5. document if recordings are ever retained.

If voice transcription is local:

- microphone permission remains sensitive,
- the app should not record in the background without an explicit feature and visible indication.

---

## Network Architecture

Preferred design:

```text
Kompakt
    ↓
HTTPS
    ↓
hardened gateway
    ↓
restricted server API
```

This may be safer than giving the phone broad access to an internal network.

If Tailscale is used:

- restrict ACLs,
- expose only required services,
- do not give the Kompakt general access to SSH, NAS, databases, or unrelated internal services.

Tailscale itself is not the concern; blast radius is.

---

## Rate Limiting and Resource Abuse

Because agent calls can be expensive:

- rate-limit device requests,
- limit concurrent agent runs,
- cap input size,
- cap attachments if introduced,
- require confirmation for expensive jobs if useful,
- reject malformed requests early.

A compromised device should not be able to create unbounded cost.

---

## Revocation

Every enrolled device must be revocable independently.

Server-side device states may include:

```text
pending
active
revoked
expired
```

Revoking a device should:

- deny new authenticated requests,
- invalidate active sessions,
- prevent new session issuance,
- optionally terminate running device-owned jobs if policy requires.

---

## Recovery

When replacing a phone:

1. install official APK,
2. generate a new device key,
3. enroll as a new device,
4. restore server-side state through normal sync,
5. keep old device revoked.

Do not copy the old device private key to the new phone.

---

## APK Signing

The official APK must be signed with a private Android release key.

The release signing key must:

- never be committed to Git,
- never be included in the APK source tree,
- never be exposed to untrusted CI,
- be backed up securely,
- be stored separately from ordinary development credentials.

Android uses signing identity to determine whether an APK is allowed to update an installed application.

Protecting the release key is therefore a major supply-chain requirement.

---

## Official Builds and Provenance

The project should clearly distinguish:

- official source,
- official release APKs,
- unofficial forks,
- locally built APKs.

Recommended release metadata:

- version,
- commit SHA,
- release notes,
- APK checksum,
- signing certificate fingerprint.

A user should be able to verify that an APK corresponds to an official release.

---

## Public Repository Security

Recommended GitHub protections:

- secret scanning,
- push protection,
- Dependabot alerts,
- dependency updates,
- code scanning where useful,
- branch protection,
- required review for sensitive workflow files,
- restricted Actions permissions,
- pinned third-party Actions,
- no production secrets in pull-request workflows.

Untrusted pull requests must not have access to:

- server production credentials,
- release signing keys,
- deployment secrets,
- privileged API credentials.

---

## CI/CD Security

CI should separate:

### Untrusted verification

Runs on pull requests.

Allowed:

- build,
- unit tests,
- lint,
- static analysis.

Must not receive:

- production secrets,
- release keys,
- deployment credentials.

### Trusted release

Runs only from trusted branches/tags or trusted manual workflows.

May access:

- release signing material,
- artifact publication credentials,

but only with minimal permissions.

---

## Dependency Security

Keep dependency count low.

For each dependency:

- prefer maintained libraries,
- pin or lock versions where practical,
- review security advisories,
- avoid unnecessary SDKs,
- avoid analytics/advertising SDKs,
- avoid broad transitive dependency trees.

This is especially important because the client is intended to remain small and auditable.

---

## Security Review Checklist

Before first public release:

- [ ] No secrets committed to Git.
- [ ] No hard-coded API credentials.
- [ ] Device enrollment implemented.
- [ ] Device revocation implemented.
- [ ] Android Keystore used.
- [ ] Server authorization enforced for every meaningful write/action.
- [ ] No arbitrary shell API.
- [ ] No arbitrary HTML/JavaScript rendering.
- [ ] Minimal Android permissions.
- [ ] Cache retention defined.
- [ ] Voice data retention defined.
- [ ] Logs reviewed for sensitive data.
- [ ] Release signing key isolated.
- [ ] CI PR workflows cannot access release or production credentials.
- [ ] Dependencies reviewed.
- [ ] Rate limits in place for expensive operations.
- [ ] Lost-device recovery tested.
- [ ] Official APK verification process documented.

---

## Open Security Questions

Still to decide:

- exact enrollment UX,
- exact request-signing/session protocol,
- whether device keys are hardware-backed on Kompakt,
- whether Tailscale is used,
- exact cache retention periods,
- whether server-side STT is enabled,
- which Class 4 actions may ever be approved from Kompakt,
- release-signing workflow,
- APK update mechanism,
- audit-log retention,
- whether certificate pinning adds enough value to justify lifecycle complexity.

These should be resolved experimentally and documented as decisions land.

---

## Security Design Principle

The most important rule for the project is:

> Possession of the complete source code must provide no authority over the server. Possession of a Kompakt device credential must provide only the minimum authority required by that device.

