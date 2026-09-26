//! Checkpoint tests (spec/wi-codec.md section 14): an observer hashes every intermediate stage, and the hashes and
//! scalars are compared with wi-checkpoints.json (the six public vectors) and wi-synthetic.json. Only hashes, sizes
//! and counts are compared or reported.

#[path = "../tests/common/mod.rs"]
mod common;

use std::collections::BTreeMap;

use serde_json::Value;

use crate::codec::{decode_with, geometry::size, Observer};
use crate::huffman::Model;
use crate::WiError;

use common::sha256_hex;

/// Every scalar and checkpoint hash of one decode, keyed as in the JSON ("C3_upsampled" per level, and so on).
#[derive(Default)]
struct Recorder {
    top: BTreeMap<&'static str, Value>,
    levels: BTreeMap<usize, BTreeMap<&'static str, Value>>,
    coefficients: BTreeMap<usize, Vec<u8>>,
}

fn region(x: &[i32], rows: usize, columns: usize) -> Vec<u8> {
    let mut out = Vec::with_capacity(rows * columns * 4);
    for r in 0..rows {
        for c in 0..columns {
            out.extend_from_slice(&x[r * crate::WIDTH + c].to_le_bytes());
        }
    }
    out
}

fn level_region(x: &[i32], level: usize) -> String {
    sha256_hex(&region(x, size(250, level), size(200, level)))
}

impl Recorder {
    fn level(&mut self, level: usize) -> &mut BTreeMap<&'static str, Value> {
        self.levels.entry(level).or_default()
    }
}

impl Observer for Recorder {
    fn header(&mut self, q: u32) {
        self.top.insert("q", q.into());
    }
    fn ll_band(&mut self, ll_bits: u32, x: &[i32]) {
        self.top.insert("ll_bits", ll_bits.into());
        self.top.insert("C1_ll", sha256_hex(&region(x, 9, 8)).into());
    }
    fn skip(&mut self, skip: usize) {
        self.top.insert("skip", skip.into());
    }
    fn map(&mut self, m: &[u8]) {
        self.top.insert("C2_map", sha256_hex(m).into());
    }
    fn sharpened(&mut self, x: &[i32]) {
        self.top.insert("C6_sharpened", sha256_hex(&region(x, 126, 101)).into());
    }
    fn upsampled(&mut self, level: usize, x: &[i32]) {
        let hash = level_region(x, level);
        let entry = self.level(level);
        entry.insert("C3_upsampled", hash.into());
        entry.insert("escape_bits", Value::Null);
    }
    fn escape_bits(&mut self, level: usize, bits: u32) {
        self.level(level).insert("escape_bits", bits.into());
    }
    fn coefficient(&mut self, level: usize, value: i32) {
        self.coefficients.entry(level).or_default().extend_from_slice(&value.to_le_bytes());
    }
    fn reconstructed(&mut self, level: usize, x: &[i32]) {
        let hash = level_region(x, level);
        let coefficients = self.coefficients.remove(&level).unwrap_or_default();
        let entry = self.level(level);
        entry.insert("C4_coefficient_count", (coefficients.len() / 4).into());
        entry.insert("C4_coefficients", sha256_hex(&coefficients).into());
        entry.insert("C5_reconstructed", hash.into());
    }
    fn finished(&mut self, bytes_consumed: usize, raster: &[u8]) {
        self.top.insert("bytes_consumed", bytes_consumed.into());
        self.top.insert("C7_raster", sha256_hex(raster).into());
        let mut upright = raster.to_vec();
        upright.reverse();
        self.top.insert("C8_portrait", sha256_hex(&upright).into());
    }
}

const TOP: [&str; 10] = [
    "q",
    "ll_bits",
    "skip",
    "bytes_consumed",
    "C1_ll",
    "C2_map",
    "C6_sharpened",
    "C7_raster",
    "C8_portrait",
    "wi_length",
];
const PER_LEVEL: [&str; 5] =
    ["C3_upsampled", "escape_bits", "C4_coefficient_count", "C4_coefficients", "C5_reconstructed"];

/// Decodes `wi` with a recorder and returns the names of every checkpoint or scalar that differs from `case`.
fn differences(wi: &[u8], case: &Value) -> Vec<String> {
    let mut recorder = Recorder::default();
    if let Err(e) = decode_with(wi, &mut recorder) {
        return vec![format!("decode failed with {}", e.code())];
    }
    recorder.top.insert("wi_length", wi.len().into());
    let mut bad: Vec<String> =
        TOP.iter().filter(|k| recorder.top.get(*k) != case.get(*k)).map(|k| k.to_string()).collect();
    if sha256_hex(wi) != case["wi_sha256"].as_str().unwrap_or("") {
        bad.push("wi_sha256".into());
    }
    let levels = case["levels"].as_array().cloned().unwrap_or_default();
    if levels.len() != 5 {
        bad.push("levels".into());
    }
    for expected in &levels {
        let level = expected["level"].as_u64().unwrap_or(99) as usize;
        let got = recorder.levels.get(&level).cloned().unwrap_or_default();
        for key in PER_LEVEL {
            if got.get(key) != expected.get(key) {
                bad.push(format!("level {level} {key}"));
            }
        }
    }
    bad
}

#[test]
fn public_vectors_match_every_checkpoint() {
    let Some(photos) = common::public_photo_sections() else {
        eprintln!("skipped: run scripts/fetch-test-vectors.sh");
        return;
    };
    let cases = common::cases("wi-checkpoints.json");
    let portraits = common::cases("public-vectors.json");
    assert_eq!(photos.len(), 6, "six public vectors");
    for ((photo, case), public) in photos.iter().zip(&cases).zip(&portraits) {
        let index = &case["index"];
        assert_eq!(differences(photo, case), Vec::<String>::new(), "public vector {index}");
        let upright = crate::decode(photo).expect("decodes");
        assert_eq!(Some(sha256_hex(&upright).as_str()), public["portrait_sha256"].as_str(), "portrait {index}");
        let native = crate::decode_native_order(photo).expect("decodes");
        assert_eq!(Some(sha256_hex(&native).as_str()), case["C7_raster"].as_str(), "raster {index}");
    }
}

#[test]
fn synthetic_valid_streams_match_every_checkpoint() {
    let valid: Vec<Value> =
        common::cases("wi-synthetic.json").into_iter().filter(|c| c.get("expected_error").is_none()).collect();
    assert_eq!(valid.len(), 41);
    for case in &valid {
        let wi = common::from_hex(case["wi_hex"].as_str().unwrap());
        let name = case["name"].as_str().unwrap();
        assert_eq!(differences(&wi, case), Vec::<String>::new(), "{name}");
    }
}

#[test]
fn synthetic_invalid_streams_fail_with_the_expected_error() {
    let invalid: Vec<Value> =
        common::cases("wi-synthetic.json").into_iter().filter(|c| c.get("expected_error").is_some()).collect();
    assert_eq!(invalid.len(), 26);
    for case in &invalid {
        let wi = common::from_hex(case["wi_hex"].as_str().unwrap());
        let got = decode_with(&wi, &mut Recorder::default()).err().map(WiError::code);
        assert_eq!(got, case["expected_error"].as_str(), "{}", case["name"]);
    }
}

/// Section 4.8: the initial codes of both models.
#[test]
fn huffman_initial_codes() {
    let m = Model::for_coefficients();
    let codes: Vec<String> = [0, 1, 2, 2048, 2049].iter().map(|&s| m.code(s)).collect();
    assert_eq!(codes, ["10", "000", "001", "01", "11"]);
    assert_eq!(m.root_weight_and_size(), (5, 9));

    let m = Model::for_map();
    assert_eq!(m.root_weight_and_size(), (66, 131));
    let table = "0 010001 1 110001 2 001001 3 011001 4 101001 5 111001 6 000101 7 001101 8 010101 9 011101 \
        10 100101 11 101101 12 110101 13 111101 14 000011 15 000111 16 001011 17 001111 18 010011 19 010111 \
        20 011011 21 011111 22 100011 23 100111 24 101011 25 101111 26 110011 27 110111 28 111011 29 111111 \
        30 000010 31 000100 32 000110 33 001000 34 001010 35 001100 36 001110 37 010000 38 010010 39 010100 \
        40 010110 41 011000 42 011010 43 011100 44 011110 45 100000 46 100010 47 100100 48 100110 49 101000 \
        50 101010 51 101100 52 101110 53 110000 54 110010 55 110100 56 110110 57 111000 58 111010 59 111100 \
        60 111110 61 0000000 62 0000010 63 0000011 256 0000001 257 100001";
    let words: Vec<&str> = table.split_whitespace().collect();
    for pair in words.chunks(2) {
        let symbol: usize = pair[0].parse().unwrap();
        assert_eq!(m.code(symbol), pair[1], "map symbol {symbol}");
    }
}

#[test]
fn level_sizes() {
    let sizes: Vec<(usize, usize)> = (0..6).map(|l| (size(250, l), size(200, l))).collect();
    assert_eq!(sizes, [(250, 200), (126, 101), (64, 51), (33, 26), (17, 14), (9, 8)]);
}
