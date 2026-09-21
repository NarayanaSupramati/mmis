# Architecture

MMIS separates public discovery from off-chain messaging.

1. A Seeker ID resolves to a Solana wallet on Mainnet.
2. The wallet identifies a MessagingEndpoint account in the MMIS registry.
3. The xx signing key and unsigned DM token identify the recipient for xxDK.
4. xxDK/cMix carries message content between devices; Solana does not carry the messages.

Raw wallets skip step 1. Direct `sp1_` addresses encode the xx endpoint and skip both Solana lookups. Direct operation still needs cMix connectivity and usable network time.

The Android Application owns the native runtime and wallet/registry controllers. JNI work runs on the runtime worker; callbacks persist local messages. A repository/view-model layer exposes state to Compose. SQLite stores conversation history and contact metadata on the device. A status of **Sent to network** describes the sender's network submission, not recipient receipt.

Mobile Wallet Adapter authorizes a wallet and obtains explicit signatures for registry changes. It does not replace the xx messaging identity. Read-only recipient discovery does not require wallet authorization. Endpoint changes require a deliberate conversation choice; a new xx identity does not inherit the old identity's conversation history.

Release/Mainnet and developer Lab/devnet are separate build profiles, with no runtime network toggle. Seeker name resolution remains Mainnet in both. See [privacy model](privacy-model.md) for what remains public and [build](build.md) for profile selection.
