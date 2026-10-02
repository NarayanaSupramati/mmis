# Hackathon security audit response

The hackathon audit was completed once. This document records the project's follow-up remediation and design responses to the findings it reported. It is not a rerun, a clean-audit claim or a security certification.

Audited commit: `726530c3bd828e969e7b2419b1b1847f7347fb55` (2 October 2026). The supplied report states **zero confirmed defects in MMIS application code**, three unconfirmed heuristic leads, dependency advisories and informational Rust-package notes. The Coach describes its coverage as partial; no broader assurance is inferred.

Remediation commit: [`b5fae0b796ea02141338430dccdb6cfd8b6857f5`](https://github.com/NarayanaSupramati/mmis/commit/b5fae0b796ea02141338430dccdb6cfd8b6857f5).

Follow-up release: **0.3.4-security**, versionCode **14**. The [release tag](https://github.com/NarayanaSupramati/mmis/releases/tag/v0.3.4-security) identifies the remediation source commit. The older 0.3.3-demo APK is unchanged and still contains BC 1.80; it does not acquire these fixes automatically.

## Finding disposition

| Audit finding | Audit severity | Status | Response |
|---|---|---|---|
| Bouncy Castle 1.80; four CVEs | Critical | FIXED: dependency updated to 1.86 | Release resolves and packages the newer artifact. The report's suggested 1.84 is insufficient for two named CVEs; see below. |
| bincode 1.3.3 unmaintained, labeled direct | Medium | TRANSITIVE / ACCEPTED UPSTREAM RISK | No direct MMIS declaration or import exists, including at the audited commit. It remains required by the pinned Anchor/Solana graph; nothing was removed or falsely reported fixed. |
| MainActivity external data to OS command | High heuristic | FALSE POSITIVE FOR SHELL / HARDENED | `command` dispatches fixed Kotlin/xxDK operations, not an OS process. Intent automation moved out of production source sets; production hooks are inert. |
| Exported MainActivity without permission | Medium heuristic | EXPECTED EXPORTED SURFACE / HARDENED | Launcher/App Link entry remains exported. Exact URI validation added; wallet/registry/backup activities remain non-exported. |
| Solana Missing Owner Check | Low heuristic | FALSE POSITIVE / DESIGN EXPLAINED | `Signer` plus wallet-derived PDA and typed Anchor account enforce authority. No owner field exists in the account struct; no ABI expansion needed. |
| License expression could not be retrieved | Info | METADATA ADDED | Crate now declares MIT, matching the existing repository license. |
| mmis-registry reported unlicensed | Info | METADATA ADDED | Same crate-manifest correction; not a new licensing decision. |
| License expression missing from manifest | Info | METADATA ADDED | `license = "MIT"` added to the actual package manifest. |
| Eleven crates with duplicate versions | Info | ACCEPTED / NO FORCED UPGRADE | Pinned upstream graph retained. Version duplication alone does not establish an exploitable defect. |

## Bouncy Castle: use and remediation

The direct dependency is declared in [app/build.gradle.kts](android-runtime-probe/app/build.gradle.kts). MMIS uses the lightweight API for Ed25519 verification of wallet signatures in [WalletDomain.kt](android-runtime-probe/app/src/main/java/network/mmis/runtime/WalletDomain.kt), and Ed25519 public-key derivation plus Blake2b-256 for restored endpoint validation in [IdentityBackup.kt](android-runtime-probe/app/src/main/java/network/mmis/runtime/IdentityBackup.kt). Its use is therefore broader than signature verification alone. Backup AES/PBKDF2 uses the platform JCA APIs.

The package is in the runtime APK. Gradle dependency insight found one direct request for this artifact, with no competing transitive version request. The change is **1.80 → 1.86**; no provider/API substitution was introduced.

The audit names CVE-2025-14813, CVE-2026-0636, CVE-2026-8763 and CVE-2026-13506. The vendor documents the first two fixes in [1.84](https://www.bouncycastle.org/resources/new-releases-bouncy-castle-java-1-84-and-bouncy-castle-java-lts-2-73-11/), but gives **1.85** as the fixed version for [CVE-2026-8763](https://github.com/bcgit/bc-java/wiki/CVE%E2%80%902026%E2%80%908763) and [CVE-2026-13506](https://github.com/bcgit/bc-java/wiki/CVE%E2%80%902026%E2%80%9013506). We selected the available [1.86 maintenance release](https://www.bouncycastle.org/resources/new-release-bouncy-castle-java-1-86/) and tested MMIS compatibility. We do not claim all four affected algorithms were reachable in MMIS, or that this upgrade audits every bundled algorithm.

## bincode provenance

`cargo tree --locked -i bincode` shows `bincode 1.3.3` through `anchor-lang 1.2.0`, `solana-instruction 4.0.0` and `solana-sysvar 3.1.1`. MMIS declares Anchor directly; bincode is not directly declared or imported by MMIS production/tests. Cargo.lock remains unchanged. The [RustSec advisory](https://rustsec.org/advisories/RUSTSEC-2025-0141.html) concerns maintenance status. Replacing the frozen Anchor/Solana stack solely to remove this transitive crate would be a separate protocol/toolchain migration. Dependency-graph presence is not proof that every crate path is included in the deployed SBF binary.

## Android command and exported-entry trace

At the audited commit the chain was `MainActivity.handle(intent)` → `FLAG_DEBUGGABLE` gate → `XxRuntime.command(name, data)` → executor → fixed `when` branches for initialization, connection, snapshot/time checks and `send`. Send validates a peer descriptor, then invokes `DMClient.sendText` through gomobile/JNI. Wallet/registry prefixes opened internal activities and invoked explicit MWA/registry operations. They did not create a shell command.

Searches of MMIS Android sources and pinned xxDK binding/runtime Go sources found no `Runtime.exec`, `ProcessBuilder`, `os/exec`, `exec.Command`, `syscall.Exec` or shell dispatch on this path. Build-host gomobile tooling uses subprocesses to build native code; it is not APK runtime dispatch. This is a scoped source/call-path review, not a full independent audit of every native dependency.

The bridge now lives in [src/debug](android-runtime-probe/app/src/debug/java/network/mmis/runtime/TestIntentBridge.kt), with allowlisted runtime operations and bounded payload parsing. [src/production](android-runtime-probe/app/src/production/java/network/mmis/runtime/TestIntentBridge.kt) has no-op implementations for Release/Hackathon. MainActivity, WalletActivity and RegistryActivity no longer parse command extras themselves. Inspection of the signed Release DEX confirms production methods only perform Kotlin parameter checks and return. A signed device test also exercised them with malformed extras and observed no dispatch.

[IncomingLaunch](android-runtime-probe/app/src/main/java/network/mmis/runtime/IncomingLaunch.kt) rejects other VIEW schemes, hosts, paths, user information, queries/fragments and nonstandard ports. MainActivity is intentionally exported; the other three activities remain non-exported. No new wallet transaction or registry mutation was used in this follow-up.

## Solana authority

[Account constraints](registry/programs/mmis-registry/src/lib.rs) require a wallet signer and `["mmis-endpoint", wallet.key()]` PDA for register, update and close. Anchor `Account<MessagingEndpoint>` checks program ownership/discriminator. Update and close also validate exact length, version, revision and non-executable state. Close refunds the signing wallet. The report's description of an owner field does not match the actual struct. Adding one would duplicate address-derived authority and change the frozen **53-byte** ABI.

Only explanatory comments and license metadata changed in registry source. No program deployment or Mainnet Program ID change occurred.

## Focused validation

See [machine-readable results](docs/security/validation.json), [dependencies before](docs/security/dependencies-before.txt) and [after](docs/security/dependencies-after.txt).

- 74 Android unit tests passed, including registry transaction construction, wallet signature rejection cases, encrypted backup validation and new App Link cases.
- Debug, Release, Hackathon and Migration Kotlin source sets compiled; production Release APK built and signature verified.
- Scoped `NewApi` / `InlinedApi` lint passed for Debug and Release.
- Two Rust ABI/PDA tests passed for each Lab and Mainnet configuration; locked SBF build passed.
- 31 existing local-validator scenarios passed, including cross-wallet update/close, missing signer, malformed owner/data and atomic rollback.
- Physical Seeker updated in place; cMix Ready, inbound message and return reply observed with an API29 emulator.
- On Seeker: RFC 8032 Ed25519 known vector passed; MWA reauthorization succeeded; real detached wallet signature verified and modified message rejected. No transaction or fee.
- Package, production certificate, minSdk29, targetSdk36, Mainnet profile, MWA identity, DAL and registry Program ID remain unchanged.

The separate signed instrumentation APK was removed from the phone after testing. These are project regression results; the hackathon audit cannot be rerun. Remaining storage, metadata, dependency, upgrade-authority and delivery risks are described in the [security model](docs/security-model.md).
