# Third-party notices

The MIT license at the repository root applies to MMIS-owned code and documentation, not to dependencies.

Pinned xxDK client and historical xxnetwork-crypto license texts are preserved here. The Android AAR is generated from those sources and their Go dependencies. Android dependencies and versions are listed in `android-runtime-probe/app/build.gradle.kts`; registry dependencies are recorded in `registry/Cargo.lock`. These components retain their respective licenses and notices.

The Gradle wrapper retains its embedded copyright/license headers. Additional native dependency license texts and a sanitized module inventory accompany the publication bundle. A module inventory can include tooling/test dependencies and is not a claim that every listed package ships in the APK.

`native-modules.json` records 437 versioned module entries; 91 had license/notice files available in the pinned local module cache and those files are included under `native/`. Entries without a cached notice are explicit, not an assertion that the module is unlicensed. This inventory is build provenance, not a comprehensive legal compliance certification.
