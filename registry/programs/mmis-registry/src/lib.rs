use anchor_lang::prelude::*;

#[cfg(not(feature = "mainnet"))]
declare_id!("Bb3evw39nAonSVCykSnjjm1vrdKhuy6MAR7R2iDQGCyJ");
#[cfg(feature = "mainnet")]
declare_id!("MmisXGzDeJbPeK9uMDpqYxQ3ED7xsjYahP9rS5AvuYz");
pub const ENDPOINT_SEED: &[u8] = b"mmis-endpoint";

#[program]
pub mod mmis_registry {
    use super::*;

    pub fn register_endpoint(ctx: Context<RegisterEndpoint>, xx_signing_public_key: [u8; 32], dm_token: u32) -> Result<()> {
        validate_endpoint(&xx_signing_public_key, dm_token)?;
        let endpoint = &mut ctx.accounts.endpoint;
        endpoint.version = 1;
        endpoint.xx_signing_public_key = xx_signing_public_key;
        endpoint.dm_token = dm_token;
        endpoint.revision = 1;
        Ok(())
    }

    pub fn update_endpoint(ctx: Context<UpdateEndpoint>, xx_signing_public_key: [u8; 32], dm_token: u32) -> Result<()> {
        validate_endpoint(&xx_signing_public_key, dm_token)?;
        let endpoint = &mut ctx.accounts.endpoint;
        require!(endpoint.xx_signing_public_key != xx_signing_public_key || endpoint.dm_token != dm_token, RegistryError::EndpointNoChange);
        let revision = endpoint.revision.checked_add(1).ok_or(RegistryError::EndpointRevisionOverflow)?;
        endpoint.xx_signing_public_key = xx_signing_public_key;
        endpoint.dm_token = dm_token;
        endpoint.revision = revision;
        Ok(())
    }

    pub fn close_endpoint(_ctx: Context<CloseEndpoint>) -> Result<()> { Ok(()) }
}

fn validate_endpoint(key: &[u8; 32], token: u32) -> Result<()> {
    require!(*key != [0; 32], RegistryError::EndpointInvalidKey);
    require!(token != 0, RegistryError::EndpointInvalidToken);
    Ok(())
}

#[account]
pub struct MessagingEndpoint {
    pub version: u8,
    pub xx_signing_public_key: [u8; 32],
    pub dm_token: u32,
    pub revision: u64,
}
impl MessagingEndpoint { pub const SPACE: usize = 8 + 1 + 32 + 4 + 8; }

#[derive(Accounts)]
pub struct RegisterEndpoint<'info> {
    #[account(mut)]
    pub wallet: Signer<'info>,
    #[account(init, payer = wallet, space = MessagingEndpoint::SPACE,
        seeds = [ENDPOINT_SEED, wallet.key().as_ref()], bump,
        constraint = !endpoint.to_account_info().executable @ RegistryError::EndpointMalformed)]
    pub endpoint: Account<'info, MessagingEndpoint>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
pub struct UpdateEndpoint<'info> {
    pub wallet: Signer<'info>,
    #[account(mut, seeds = [ENDPOINT_SEED, wallet.key().as_ref()], bump,
        constraint = endpoint.to_account_info().data_len() == MessagingEndpoint::SPACE @ RegistryError::EndpointMalformed,
        constraint = !endpoint.to_account_info().executable @ RegistryError::EndpointMalformed,
        constraint = endpoint.version == 1 @ RegistryError::EndpointUnsupportedVersion,
        constraint = endpoint.revision != 0 @ RegistryError::EndpointMalformed)]
    pub endpoint: Account<'info, MessagingEndpoint>,
}

#[derive(Accounts)]
pub struct CloseEndpoint<'info> {
    #[account(mut)]
    pub wallet: Signer<'info>,
    #[account(mut, close = wallet, seeds = [ENDPOINT_SEED, wallet.key().as_ref()], bump,
        constraint = endpoint.to_account_info().data_len() == MessagingEndpoint::SPACE @ RegistryError::EndpointMalformed,
        constraint = !endpoint.to_account_info().executable @ RegistryError::EndpointMalformed,
        constraint = endpoint.version == 1 @ RegistryError::EndpointUnsupportedVersion,
        constraint = endpoint.revision != 0 @ RegistryError::EndpointMalformed)]
    pub endpoint: Account<'info, MessagingEndpoint>,
}

#[error_code]
pub enum RegistryError {
    #[msg("Endpoint already registered")]
    EndpointAlreadyRegistered,
    #[msg("Endpoint not registered")]
    EndpointNotRegistered,
    #[msg("Endpoint unchanged")]
    EndpointNoChange,
    #[msg("Endpoint revision overflow")]
    EndpointRevisionOverflow,
    #[msg("Invalid xx signing public key")]
    EndpointInvalidKey,
    #[msg("Invalid DM token")]
    EndpointInvalidToken,
    #[msg("Unsupported endpoint version")]
    EndpointUnsupportedVersion,
    #[msg("Malformed endpoint")]
    EndpointMalformed,
}
