# Privacy model and beta boundaries

Solana supplies public identity/discovery, not message privacy. Wallet-to-xx registry associations are public and historically observable. `.skr` ownership is public identity information; a name is not an encryption key. Disabling a listing cannot erase earlier observations.

Message content travels through xxDK/cMix, not Solana. MMIS does not claim absolute anonymity, untraceability or that all metadata is impossible to observe. A wallet association can link an otherwise separately managed messaging identity to a public wallet.

Local history and aliases reside in app-private SQLite on the user's device, in plaintext. Messaging identity, native storage secrets and wallet authorization are sensitive app-private state. Device compromise is outside any promise made by this beta. Android cloud backup is disabled.

The supported encrypted export includes the messaging identity and a logical history snapshot. It excludes wallet authorization, RPC credentials and the native network session. A restore can recover the xx endpoint; it is not multi-device history synchronization or recovery of in-flight sends. Store the encrypted file and its password safely and separately. Losing both the device identity and a usable backup loses that identity.

Send failures/timeouts can precede late arrival. Retry is an explicit new attempt and may produce duplicate content. **Sent to network** does not mean Delivered or Read. Foreground banners are not background push notifications.

The Mainnet registry is upgradeable. Public RPC services and network-time reachability affect availability. Android 10+ is supported on the tested 4 KB page-size baseline; 16 KB kernel support is not claimed. This remains a hackathon/beta release.
