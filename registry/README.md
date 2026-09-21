# MMIS endpoint registry

Public discovery/control-plane program; message content is transported through cMix and never stored in this registry.

Mainnet program: `MmisXGzDeJbPeK9uMDpqYxQ3ED7xsjYahP9rS5AvuYz`. **Protocol v1 · Upgradeable beta.**

[Protocol and account layout](../docs/registry.md) · [Portable build](../docs/build.md#registry-linux--wsl) · [Mainnet deployment metadata](deployments/mainnet.json)

Default Cargo feature selection and Anchor provider are developer Lab/devnet. Add `--features mainnet` for the Mainnet Program ID. Neither a build nor a unit test deploys the program. Deployment keys and operator mutation scripts are not included.
