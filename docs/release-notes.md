# MMIS 0.3.4 Security

Android 10+ · Solana Mainnet · Hackathon beta

- Bouncy Castle updated from 1.80 to 1.86; compatible wallet-signature and backup validation paths tested.
- Automation intent parsing isolated to Debug/Migration; Release/Hackathon hooks are inert.
- Exact App Link URI validation with regression tests.
- Registry authority documentation and MIT crate metadata; no ABI or on-chain program change.
- Public [audit response](../SECURITY-AUDIT-RESPONSE.md) and [security model](security-model.md), including remaining transitive bincode risk.

Validation: 74 Android unit tests, scoped API lint, Rust ABI/PDA tests, 31 local-validator scenarios, production signature verification, physical Seeker two-way cMix smoke and MWA detached-message signature verification. This is project regression evidence, not an independent audit rerun.

[Download APK](https://github.com/NarayanaSupramati/mmis/releases/download/v0.3.4-security/mmis-0.3.4-security.apk). VersionCode 14, package `network.mmis.runtime`, minSdk29, targetSdk36. Production certificate and Mainnet identity are unchanged; installs over the official 0.3.3-demo APK without clearing data.

SHA-256: `ff4c43a58a32ab91e5b7715cf86acd874050d700bf6681e12fc0942abd805920`. Size: 193273992 bytes. See [release metadata](../release.json).

Known limitations remain: foreground notifications, possible timeout/late arrival, no delivery/read receipt, plaintext app-private history, upgradeable registry and 4 KB native baseline.

## Previous demo release

The [0.3.3-demo release](https://github.com/NarayanaSupramati/mmis/releases/tag/v0.3.3-demo) and its demonstration video remain available as historical artifacts. Its APK still contains BC 1.80; prefer 0.3.4-security. Old artifact bytes were not replaced.
