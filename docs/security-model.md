# Security model and beta boundaries

MMIS separates public recipient discovery from off-chain message transport. This document describes the implemented boundaries, not a guarantee of anonymity or an independent security certification. See the [audit response](../SECURITY-AUDIT-RESPONSE.md).

## Security boundaries

| Boundary | Responsibility |
|---|---|
| Solana wallet | Authorizes registry mutations through the wallet app; wallet keys are not imported into MMIS. |
| xx messaging identity | Independent identity used by xxDK/cMix. A connected wallet cannot recreate a lost xx identity. |
| Mainnet registry | Public mapping from a wallet-derived PDA to a messaging endpoint. It contains addressing data, not conversation content. |
| xxDK/cMix | Off-chain message transport, implemented by the pinned native SDK. |
| Android storage | App-private identity/session state, wallet authorization and plaintext local SQLite history. Device compromise can expose them. |
| Backup file | Password-encrypted export of messaging identity and a logical history/contact snapshot; not wallet recovery or live multi-device synchronization. |

## Public information

Wallet-to-xx endpoint associations, `.skr` ownership and program state are public and historically observable. Removing or replacing a listing does not erase earlier observations. Endpoint public keys and DM tokens are addressing data, not wallet secrets or proof of ownership. Publishing a listing links otherwise separate identities.

## Off-chain information and cMix claims

Message content and conversation history are not stored on Solana. xxDK/cMix carries messages off-chain; received history is stored locally on the device. Sending a message requires no Solana transaction. This does not imply zero observable metadata or absolute anonymity: devices, peers, RPC services and networks remain separate trust boundaries.

MMIS provides no delivery or read-receipt guarantee. A timeout can precede late arrival; an explicit retry can duplicate content. **Sent to network** records submission status only. Local contact aliases do not verify a sender's wallet or identity.

## Registry authority

All register/update/close contexts require `wallet: Signer` and the PDA seeds `[b"mmis-endpoint", wallet.key().as_ref()]`. Anchor's typed `Account<MessagingEndpoint>` validates program ownership and the discriminator. Update/close also constrain exact length, version, revision and non-executable state. Close returns rent to the signing wallet.

The wallet key is encoded into the address; it is intentionally not duplicated in the **53-byte** account data. This account has no `owner` field. A different signer derives a different PDA and cannot mutate the original endpoint. Registry v1 ABI and Mainnet Program ID remain unchanged. The registry is upgradeable: program upgrade authority is a separate trust boundary from an individual listing's wallet authority.

## Local storage and backups

The live SQLite database is **plaintext app-private storage**. Encrypted export does not encrypt that database. Android cloud backup is disabled. A compromised/unlocked device, an authorized debugging environment or malicious software with equivalent access can expose sensitive local state.

The backup uses an AES-256-GCM outer envelope with PBKDF2-HMAC-SHA256 (600,000 iterations, random salt) and contains the native encrypted identity export. It includes a logical history/contact snapshot, and excludes wallet keys, wallet authorization, RPC credentials and the native network session. Password loss makes the export unusable; a weak or disclosed password can expose it. Store the file and password separately. Java/JNI copies prevent a guarantee that every secret is immediately erased from memory.

## Identity recovery

Restore of an exact usable backup preserves the xx endpoint. Losing the device identity and all usable backups loses that messaging identity; reconnecting the Solana wallet does not recover it. A wallet-authorized registry rebind can point to an intentionally changed identity, but cannot decrypt messages addressed to the old identity or recover its history. The current UI does not provide general in-place identity rotation or history merging.

## App Links and Mobile Wallet Adapter

`MainActivity` is exported for launcher and verified App Links. Runtime validation accepts HTTPS, the exact `narayanasupramati.github.io` host and `/mmis/open` path, with no user information, query or fragment, and only the default/443 port. A link opens the UI; it cannot supply a signing or registry command. Normal foreground startup can reconcile the status of an already pending operation.

WalletActivity, RegistryActivity and IdentityBackupActivity remain non-exported. Release/Hackathon variants do not parse or dispatch automation intent extras; that implementation exists only in Debug/Migration. Laboratory builds are not equivalent to the production security boundary.

The production package and signing certificate are associated with the website through Digital Asset Links. MWA authorization/signing remains subject to the wallet's approval policy; a previously authorized session may reauthorize without a new prompt. A link or DAL association is not authorization to sign a transaction.

## Known limitations

- Hackathon beta reliability; foreground banners only, no background/system-notification guarantee.
- No read receipts, guaranteed arrival or zero-metadata claim.
- Android 10+ on the tested 4 KB native page-size baseline; 16 KB kernel support is not claimed.
- Upgradeable registry, external RPC and network-time availability dependencies.
- Frozen xxDK native dependency; updating Java crypto does not amount to a native SDK audit.
- Unmaintained `bincode` remains transitive in the pinned Anchor/Solana build graph; see the audit response.
