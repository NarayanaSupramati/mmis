# MMIS endpoint registry

**Solana Mainnet · Protocol v1 · Upgradeable beta**

Program: `MmisXGzDeJbPeK9uMDpqYxQ3ED7xsjYahP9rS5AvuYz`

PDA seeds: `["mmis-endpoint", wallet]`, with the wallet's 32 public-key bytes.

The wallet signs register, update and close operations. The PDA supplies the wallet association; it is not another message mailbox. Closing a listing removes the current account, not historical observations of that binding.

| Account field | Bytes |
|---|---:|
| Anchor discriminator | 8 |
| version | 1 |
| xx signing public key | 32 |
| dmToken, unsigned little-endian | 4 |
| revision, unsigned little-endian | 8 |

Total account data: **53 bytes**. Instructions: `register_endpoint`, `update_endpoint`, `close_endpoint`. Frozen ABI fixtures are in [fixtures](fixtures/) and the [Mainnet IDL](../registry/idl/mainnet/mmis_registry.json).

The registry stores public discovery data. Message text and conversation history never enter its instruction/account payloads. Mainnet identity and binary/IDL hashes are in [deployment metadata](../registry/deployments/mainnet.json). The developer Lab/devnet deployment is separate; do not substitute it for Mainnet.
