//! Test helpers shared by the unit tests (src/checkpoint_tests.rs) and the integration tests: the JSON vectors, the
//! six public card vectors (decrypted here with the published public keys), and SHA-256.
//!
//! The public vectors hold what look like real people's details: nothing here prints or saves them.

#![allow(dead_code)]

use std::path::PathBuf;

use num_bigint::BigUint;
use sha2::{Digest, Sha256};

pub fn repo_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..")
}

pub fn vectors(name: &str) -> serde_json::Value {
    let path = repo_root().join("spec/test-vectors").join(name);
    let text = std::fs::read_to_string(&path).expect("test vector file");
    serde_json::from_str(&text).expect("valid JSON")
}

pub fn cases(name: &str) -> Vec<serde_json::Value> {
    vectors(name)["cases"].as_array().expect("cases").clone()
}

pub fn sha256_hex(data: &[u8]) -> String {
    Sha256::digest(data).iter().map(|b| format!("{b:02x}")).collect()
}

pub fn from_hex(s: &str) -> Vec<u8> {
    (0..s.len() / 2).map(|i| u8::from_str_radix(&s[2 * i..2 * i + 2], 16).expect("hex")).collect()
}

/// The valid synthetic streams of wi-synthetic.json (made-up content, safe to mutate and to report on).
pub fn synthetic_valid_streams() -> Vec<Vec<u8>> {
    cases("wi-synthetic.json")
        .iter()
        .filter(|c| c.get("expected_error").is_none())
        .map(|c| from_hex(c["wi_hex"].as_str().unwrap()))
        .collect()
}

/// Whether public vectors are required (fail instead of skipping when missing).
pub fn vectors_required() -> bool {
    std::env::var("SADL_REQUIRE_VECTORS").map(|v| v == "1" || v == "true").unwrap_or(false)
}

const VERSION_2: [u8; 4] = [0x01, 0x9B, 0x09, 0x45];
const VERSION_1: [u8; 4] = [0x01, 0xE1, 0x02, 0x45];

// The published public keys (as in spec/card-barcode.md): (modulus, exponent) for the 128-byte blocks, then for the
// 74-byte block. They only undo the encryption; they are not secrets.
const KEYS_V1: [(&str, &str); 2] = [
    (
        "fed2e1c27e3363316e77317a7a52c54981395186be4974760c72518d63e0544a48d088b332c5b0c370c765d65d983c1f9de0a42b310ccc07ae770bd2b61d6a4dcceac757689bdcbf608478faf312f6087cc496c3762cf5c4651caecda3499fae7edb7eb40e3e18eb304170e91ed5b156aace6f432d6eca6cc35851de8c678f67",
        "bb797ffdec7f9e42c9d6f79b137059db",
    ),
    (
        "ff3cec6b5f40e3c3661451b9fcfaef3aeb06dc2329c0e6f4dccc9279726716ce15bbe05eed2c5711bcf8f5b6c8f7276db5c43bfaa3040dc01ab14b9c4d16f71c0ce5ea953f0c754c6b17",
        "db05ba822d9acc33fab7d8f427f9ce65",
    ),
];
const KEYS_V2: [(&str, &str); 2] = [
    (
        "ca9f18ef6c3f3fa4c5a461fea54ab19406ba5ecd746d60a27492dca3d74e3b5c1d315f7b10383241809b029ebbd5de4d116030cc57f7d5a6c9a16f373bb14a508523f7e80a4c744d9085663a4a1472d7af2c56ae41b5065f7efa0293bd3278ad693546f9f16219b79ff471a3636824cffcdb63a8ed8059e6b9a4f0db895381cb",
        "187092da6454ceb1853e6915f8466a05",
    ),
    (
        "b404a0df11d1cacff1a1a048d4d573f953a62c583d74925927561a6d7a1e2b14042526af70b550547390ea6ec748d30fdb81adb490e0c36a1986b404b2f5f69ef5da1b663e59509130e7",
        "309cfed9719fe2a5e20c9bb44765382b",
    ),
];

/// Textbook RSA per block with the public key, marker check, markers removed: the 684-byte payload.
fn decrypt(raw: &[u8]) -> Vec<u8> {
    assert_eq!(raw.len(), 720, "card length");
    let keys = if raw[..4] == VERSION_2 {
        KEYS_V2
    } else if raw[..4] == VERSION_1 {
        KEYS_V1
    } else {
        panic!("unknown version bytes")
    };
    let mut out = Vec::with_capacity(684);
    for k in 0..6 {
        let size = if k < 5 { 128 } else { 74 };
        let start = 6 + 128 * k;
        let (n, e) = keys[if k < 5 { 0 } else { 1 }];
        let n = BigUint::parse_bytes(n.as_bytes(), 16).unwrap();
        let e = BigUint::parse_bytes(e.as_bytes(), 16).unwrap();
        let m = BigUint::from_bytes_be(&raw[start..start + size]).modpow(&e, &n).to_bytes_be();
        assert!(m.len() <= size, "block {k} too long");
        let mut block = vec![0u8; size - m.len()];
        block.extend_from_slice(&m);
        for j in 1..=5u32 {
            assert_eq!(u32::from(block[j as usize - 1]), (j << k) & 0x7F, "block {k} marker");
        }
        out.extend_from_slice(&block[5..]);
    }
    out
}

/// Section 3 of a payload, including its "WI" header (spec/portrait.md).
fn photo_section(p: &[u8]) -> Vec<u8> {
    let start = 10 + p[5] as usize + p[7] as usize;
    let size = ((p[8] as usize) << 8 | p[9] as usize) & 0x0FFF;
    p[start..start + size].to_vec()
}

/// The six public vectors' photo sections, in the order of the constants in UnitTest1.cs, or None when
/// scripts/fetch-test-vectors.sh has not run (a failure instead when SADL_REQUIRE_VECTORS is set).
pub fn public_photo_sections() -> Option<Vec<Vec<u8>>> {
    let path = repo_root().join("third_party/Reply.Net.SADL/Reply.Net.SADL/Reply.Net.SADL.Tests/UnitTest1.cs");
    let Ok(src) = std::fs::read_to_string(&path) else {
        assert!(!vectors_required(), "public vectors missing: run scripts/fetch-test-vectors.sh");
        return None;
    };
    let mut photos = Vec::new();
    let mut rest = src.as_str();
    while let Some(at) = rest.find("const string ") {
        rest = &rest[at + "const string ".len()..];
        let Some(eq) = rest.find(" = \"") else { break };
        let after = &rest[eq + 4..];
        let end = after.find('"').unwrap_or(0);
        let hex = &after[..end];
        rest = &after[end..];
        if !hex.is_empty() && hex.chars().all(|c| c.is_ascii_hexdigit()) {
            photos.push(photo_section(&decrypt(&from_hex(hex))));
        }
    }
    Some(photos)
}
