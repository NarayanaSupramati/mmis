# Build from source

## Android

Install JDK17 and Android SDK platform36/build-tools35.0.0. Set `JAVA_HOME` and `ANDROID_HOME` to your own installation directories (or create an ignored `android-runtime-probe/local.properties` with `sdk.dir`). Use the included Gradle8.13 wrapper, AGP8.12.0 and Kotlin2.2.10. No production key or paid RPC is needed for a Debug build.

From the repository root:

```sh
cd android-runtime-probe
./gradlew testDebugUnitTest assembleDebug
```

Windows: replace `./gradlew` with `gradlew.bat`. Output: `app/build/outputs/apk/debug/app-debug.apk`.

| Task | Profile | Signing |
|---|---|---|
| `assembleDebug` | Lab / devnet registry | local Debug certificate |
| `assembleMigration` | Release / Mainnet | local Debug certificate; developer testing only |
| `assembleRelease` / `assembleHackathon` | Release / Mainnet | private production configuration required; fail closed |

All variants default to `network.mmis.runtime`. For a separate developer installation, Debug/Migration accept `-PprobeApplicationId=network.mmis.runtime.dev`; rebuild the intended variant after changing this property because output filenames are shared. A locally generated Debug certificate is not the official certificate and cannot update a production install. Keep device data and use supported backup/restore before any deliberate signing-identity migration.

Official signing properties are deliberately absent. Maintainers provide their own ignored file via `-PmmisSigningProperties=<private-file>`. Release/Hackathon require it; do not copy a keystore or passwords into the repository. Debug-signed builds do not have production Digital Asset Links identity.

Developer Lab uses the devnet registry. Official Release uses Mainnet. `.skr` resolution uses Mainnet in both. Public RPC defaults are compiled in; credentials are not. Optional app-private RPC configuration is operator-managed; there is not yet a production settings flow for it.

## Pinned native library

`app/libs/bindings.aar` is the unchanged xxDK4.7.9 baseline, included for consumer builds. SHA-256:

```text
e2fca235fa31af4024acbf1ec7a41839aa9b910cd1ecd74b689fb9ee99eebcb3
```

Gradle verifies this hash before building. The AAR is 77,972,984 bytes. Do not substitute an AAR from another release.

The frozen native binary's Go module metadata retains two non-secret build-machine source paths for local replacements. They contain no user name or credentials. They are retained to preserve the verified native artifact; public build commands do not depend on them.

Native source pins:

- [elixxir-client](https://github.com/xxfoundation/elixxir-client/tree/5620c7b433c0d5968a02376244d7f4e0a0ab99f4): `5620c7b433c0d5968a02376244d7f4e0a0ab99f4`.
- [xxnetwork-crypto](https://github.com/xxfoundation/xxnetwork-crypto/tree/1dfeb262abb240b1593784826e8029ae1fcba658): `1dfeb262abb240b1593784826e8029ae1fcba658`.
- x/mobile: `v0.0.0-20240112133503-c713f31d574b`.

For the original Windows native build, check out those exact clean revisions at `temp/elixxir-client-v4.7.9` and `temp/reconstruction/xxnetwork-crypto`, extract official Go1.21.5 Windows/amd64 under `temp/toolchains/go1.21.5/go`, install NDK27.1.12297006 and Temurin17.0.20.1+1, set the SDK/JDK environment variables, then run from the repository root:

```powershell
./scripts/build-android-xxdk.ps1 -Output artifacts/android-xxdk/rebuild
```

The output directory must be new. The script keeps checksum verification enabled, checks the historical crypto source against expected `h1:28a1F36cgx4a0NPMP5OC8O0n95aGwoToVbE5kLPA3xE=`, uses an isolated local source replacement, and pins gomobile's gobind installation with an overlay. It does not rewrite upstream locks. Native rebuilds are a separate provenance workflow; ordinary Android builds consume the supplied hash-checked AAR. Original local native runs matched byte-for-byte; this is not a claim that all host/toolchain combinations produce identical bytes.

## Registry (Linux / WSL)

Pinned baseline: host Rust1.97.1, Solana CLI/build-sbf3.1.10, platform-tools v1.52 (SBF Rust1.89.0), Anchor CLI/crate1.2.0. Keep `Cargo.lock` and use locked builds.

```sh
cd registry
cargo test --locked --workspace
cargo test --locked --workspace --features mainnet
cargo build-sbf --manifest-path programs/mmis-registry/Cargo.toml --tools-version v1.52 --sbf-out-dir target/mainnet-deploy --features mainnet -- --locked
```

Mainnet output: `target/mainnet-deploy/mmis_registry.so`. Default feature selection is developer Lab/devnet; `mainnet` selects the public Mainnet Program ID without changing the v1 ABI. Checked-in IDLs are under `idl/`. Building does not deploy, fund accounts or change a listing. Deployment keys and operator mutation scripts are not included.
