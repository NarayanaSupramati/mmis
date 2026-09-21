use anchor_lang::{AccountSerialize, Discriminator, InstructionData, prelude::Pubkey};
use ::mmis_registry::*;

#[test]
fn frozen_account_and_instruction_abi() {
    let fixtures: serde_json::Value = serde_json::from_str(include_str!("../../../../docs/fixtures/test-vectors.json")).unwrap();
    assert_eq!(hex::encode(MessagingEndpoint::DISCRIMINATOR), fixtures["discriminatorHex"].as_str().unwrap());
    for fixture in fixtures["valid"].as_array().unwrap() {
        let key: [u8; 32] = hex::decode(fixture["xxSigningPublicKeyHex"].as_str().unwrap()).unwrap().try_into().unwrap();
        let endpoint = MessagingEndpoint { version: 1, xx_signing_public_key: key, dm_token: fixture["dmToken"].as_u64().unwrap() as u32, revision: fixture["revision"].as_str().unwrap().parse().unwrap() };
        let mut bytes = Vec::new(); endpoint.try_serialize(&mut bytes).unwrap();
        assert_eq!(bytes.len(), 53);
        assert_eq!(hex::encode(bytes), fixture["hex"].as_str().unwrap());
    }
    let xx_signing_public_key = core::array::from_fn(|i| i as u8);
    let dm_token = 3823185488;
    let encoded = [instruction::RegisterEndpoint { xx_signing_public_key, dm_token }.data(), instruction::UpdateEndpoint { xx_signing_public_key, dm_token }.data(), instruction::CloseEndpoint {}.data()];
    for (bytes, fixture) in encoded.iter().zip(fixtures["instructions"].as_array().unwrap()) {
        assert_eq!(hex::encode(bytes), fixture["dataHex"].as_str().unwrap());
    }
}

#[test]
fn export_test_only_pda_vectors() {
    let program = Pubkey::new_from_array([91; 32]);
    let vectors: Vec<_> = [1u8, 2, 127, 255].iter().map(|v| {
        let wallet = Pubkey::new_from_array([*v; 32]);
        let (pda, bump) = Pubkey::find_program_address(&[ENDPOINT_SEED, wallet.as_ref()], &program);
        serde_json::json!({"programId":program.to_string(),"wallet":wallet.to_string(),"pda":pda.to_string(),"bump":bump})
    }).collect();
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../docs/fixtures/pda-vectors.json");
    std::fs::create_dir_all(path.parent().unwrap()).unwrap();
    std::fs::write(path, serde_json::to_string_pretty(&vectors).unwrap()).unwrap();
}
