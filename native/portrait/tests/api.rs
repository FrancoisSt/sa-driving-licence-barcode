//! Tests of the public Rust API and the C API: the header gate, the error mapping, robustness against random and
//! mutated input (synthetic streams only, never the public vectors), and concurrent use. Only hashes, sizes, counts
//! and statuses are compared or reported.

mod common;

use std::ffi::CStr;

use sadl_portrait::ffi::{
    sadl_portrait_decode, sadl_portrait_decode_native_order, sadl_portrait_status_name, SADL_PORTRAIT_BAD_ARGUMENT,
    SADL_PORTRAIT_DECODE_FAILED, SADL_PORTRAIT_OK, SADL_PORTRAIT_UNSUPPORTED,
};
use sadl_portrait::{decode, decode_native_order, WiError, MAX_WI_BYTES, PIXELS};

fn synthetic(name: &str) -> (Vec<u8>, serde_json::Value) {
    let case = common::cases("wi-synthetic.json").into_iter().find(|c| c["name"] == name).expect("case");
    (common::from_hex(case["wi_hex"].as_str().unwrap()), case)
}

/// A small deterministic generator (xorshift64*), so failures are reproducible without a dependency.
struct Rng(u64);

impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 >> 12;
        self.0 ^= self.0 << 25;
        self.0 ^= self.0 >> 27;
        self.0.wrapping_mul(0x2545_F491_4F6C_DD1D)
    }
    fn below(&mut self, n: usize) -> usize {
        (self.next() % n as u64) as usize
    }
    fn byte(&mut self) -> u8 {
        self.next() as u8
    }
}

#[test]
fn header_gate_accepts_only_the_licence_profile() {
    let (valid, _) = synthetic("random-01");
    assert!(decode(&valid).is_ok());
    for at in [0usize, 1, 2, 3, 4, 5, 6, 9, 10, 11] {
        for value in 0..=255u8 {
            if value == valid[at] {
                continue;
            }
            let mut data = valid.clone();
            data[at] = value;
            assert_eq!(decode(&data), Err(WiError::Unsupported), "byte {at}");
        }
    }
    for value in 0..=255u8 {
        let mut data = valid.clone();
        data[7] = value;
        let unsupported = decode(&data) == Err(WiError::Unsupported);
        assert_eq!(unsupported, value != 0x42 && value != 0x43, "byte 7");
        let mut data = valid.clone();
        data[8] = value;
        let unsupported = decode(&data) == Err(WiError::Unsupported);
        assert_eq!(unsupported, value % 2 == 1, "byte 8");
    }
}

#[test]
fn size_limits() {
    let (valid, _) = synthetic("random-01");
    assert_eq!(decode(&[]), Err(WiError::Unsupported));
    assert_eq!(decode(&valid[..12]), Err(WiError::Unsupported));
    let mut thirteen = valid[..12].to_vec();
    thirteen.push(0);
    assert_eq!(decode(&thirteen), Err(WiError::EndOfData));
    let mut padded = valid.clone();
    padded.resize(MAX_WI_BYTES, 0);
    assert_eq!(decode(&padded), decode(&valid), "trailing zeros are ignored");
    padded.push(0);
    assert_eq!(decode(&padded), Err(WiError::Unsupported));
}

#[test]
fn upright_is_the_native_order_reversed() {
    let (valid, case) = synthetic("random-05");
    let native = decode_native_order(&valid).unwrap();
    let mut upright = decode(&valid).unwrap();
    assert_eq!(upright.len(), PIXELS);
    assert_eq!(Some(common::sha256_hex(&upright).as_str()), case["C8_portrait"].as_str());
    upright.reverse();
    assert_eq!(upright, native);
}

#[test]
fn error_codes_and_messages_hold_no_data() {
    let all = [
        WiError::Unsupported,
        WiError::EndOfData,
        WiError::SkipTooLarge,
        WiError::EscapeTooWide,
        WiError::RunNotExhausted,
        WiError::BadArgument,
    ];
    let codes: Vec<&str> = all.iter().map(|e| e.code()).collect();
    assert_eq!(codes, ["E1", "E2", "E3", "E4", "E5", "bad_argument"]);
    for e in all {
        assert!(e.to_string().starts_with(e.code()));
    }
}

#[test]
fn c_api_matches_the_rust_api() {
    let mut out = vec![0xAAu8; PIXELS];
    for case in common::cases("wi-synthetic.json") {
        let wi = common::from_hex(case["wi_hex"].as_str().unwrap());
        let name = case["name"].as_str().unwrap();
        out.fill(0xAA);
        let status = unsafe { sadl_portrait_decode(wi.as_ptr(), wi.len(), out.as_mut_ptr()) };
        match case["expected_error"].as_str() {
            None => {
                assert_eq!(status, SADL_PORTRAIT_OK, "{name}");
                assert_eq!(Some(common::sha256_hex(&out).as_str()), case["C8_portrait"].as_str(), "{name}");
                let status = unsafe { sadl_portrait_decode_native_order(wi.as_ptr(), wi.len(), out.as_mut_ptr()) };
                assert_eq!(status, SADL_PORTRAIT_OK, "{name}");
                assert_eq!(Some(common::sha256_hex(&out).as_str()), case["C7_raster"].as_str(), "{name}");
            }
            Some(code) => {
                let expected = if code == "E1" { SADL_PORTRAIT_UNSUPPORTED } else { SADL_PORTRAIT_DECODE_FAILED };
                assert_eq!(status, expected, "{name}");
                assert!(out.iter().all(|&b| b == 0xAA), "{name}: output written on failure");
            }
        }
    }
}

#[test]
fn c_api_rejects_null_pointers_and_names_statuses() {
    let (valid, _) = synthetic("random-01");
    let mut out = vec![0u8; PIXELS];
    unsafe {
        assert_eq!(sadl_portrait_decode(std::ptr::null(), 0, out.as_mut_ptr()), SADL_PORTRAIT_BAD_ARGUMENT);
        assert_eq!(sadl_portrait_decode(valid.as_ptr(), valid.len(), std::ptr::null_mut()), SADL_PORTRAIT_BAD_ARGUMENT);
        assert_eq!(
            sadl_portrait_decode_native_order(std::ptr::null(), 5, out.as_mut_ptr()),
            SADL_PORTRAIT_BAD_ARGUMENT
        );
        assert_eq!(sadl_portrait_decode(valid.as_ptr(), 0, out.as_mut_ptr()), SADL_PORTRAIT_UNSUPPORTED);
        assert_eq!(sadl_portrait_decode(valid.as_ptr(), 675, out.as_mut_ptr()), SADL_PORTRAIT_UNSUPPORTED);
    }
    let names: Vec<String> = (0..6)
        .map(|s| unsafe { CStr::from_ptr(sadl_portrait_status_name(s)) }.to_string_lossy().into_owned())
        .collect();
    assert_eq!(
        names,
        [
            "SADL_PORTRAIT_OK",
            "SADL_PORTRAIT_UNSUPPORTED",
            "SADL_PORTRAIT_DECODE_FAILED",
            "SADL_PORTRAIT_UNEXPECTED_RASTER",
            "SADL_PORTRAIT_BAD_ARGUMENT",
            "SADL_PORTRAIT_UNKNOWN_STATUS",
        ]
    );
    assert!(!sadl_portrait_status_name(-1).is_null());
}

#[test]
fn concurrent_calls_give_the_same_result() {
    let streams = common::synthetic_valid_streams();
    let expected: Vec<Vec<u8>> = streams.iter().map(|s| decode(s).unwrap()).collect();
    std::thread::scope(|scope| {
        for t in 0..8 {
            let (streams, expected) = (&streams, &expected);
            scope.spawn(move || {
                let mut out = vec![0u8; PIXELS];
                for round in 0..3 {
                    for (i, s) in streams.iter().enumerate().skip((t + round) % 3) {
                        let status = unsafe { sadl_portrait_decode(s.as_ptr(), s.len(), out.as_mut_ptr()) };
                        assert_eq!(status, SADL_PORTRAIT_OK);
                        assert!(out == expected[i], "stream {i} differs under concurrency");
                    }
                }
            });
        }
    });
}

/// At least 100,000 inputs: random bytes, random bytes behind a valid header, and mutations and truncations of the
/// synthetic streams. Each must decode to 50,000 bytes or fail with an error; nothing may panic.
#[test]
fn robustness_against_random_and_mutated_input() {
    let iterations: usize = std::env::var("SADL_FUZZ_ITERATIONS").ok().and_then(|v| v.parse().ok()).unwrap_or(100_000);
    let seeds = common::synthetic_valid_streams();
    let header = seeds[0][..12].to_vec();
    let mut rng = Rng(0x5AD1_2026_0926_0001);
    let mut counts = [0usize; 6]; // ok, E1..E5
    for n in 0..iterations {
        let data: Vec<u8> = match n % 4 {
            0 => {
                let len = rng.below(700);
                (0..len).map(|_| rng.byte()).collect()
            }
            1 => {
                let len = 1 + rng.below(662);
                header.iter().copied().chain((0..len).map(|_| rng.byte())).collect()
            }
            _ => {
                let mut d = seeds[rng.below(seeds.len())].clone();
                for _ in 0..1 + rng.below(6) {
                    let at = if rng.below(10) == 0 { rng.below(d.len()) } else { 12 + rng.below(d.len() - 12) };
                    match rng.below(3) {
                        0 => d[at] ^= 1 << rng.below(8),
                        1 => d[at] = rng.byte(),
                        _ => d.insert(at, rng.byte()),
                    }
                }
                if rng.below(4) == 0 {
                    d.truncate(rng.below(d.len() + 1));
                }
                d
            }
        };
        match decode(&data) {
            Ok(pixels) => {
                assert_eq!(pixels.len(), PIXELS);
                counts[0] += 1;
            }
            Err(WiError::Unsupported) => counts[1] += 1,
            Err(WiError::EndOfData) => counts[2] += 1,
            Err(WiError::SkipTooLarge) => counts[3] += 1,
            Err(WiError::EscapeTooWide) => counts[4] += 1,
            Err(WiError::RunNotExhausted) => counts[5] += 1,
            Err(WiError::BadArgument) => panic!("bad argument from the Rust API"),
        }
    }
    eprintln!("robustness: {iterations} inputs; ok, E1, E2, E3, E4, E5 = {counts:?}");
    assert_eq!(counts.iter().sum::<usize>(), iterations);
    assert!(counts[0] > 0 && counts[2] > 0 && counts[5] > 0);
}

#[test]
#[ignore = "timing only: cargo test --release -- --ignored --nocapture timing"]
fn timing() {
    let streams = common::public_photo_sections().unwrap_or_else(common::synthetic_valid_streams);
    let start = std::time::Instant::now();
    let rounds = 200;
    for _ in 0..rounds {
        for s in &streams {
            let _ = decode(s);
        }
    }
    let each = start.elapsed().as_secs_f64() / (rounds * streams.len()) as f64;
    eprintln!("one decode: {:.3} ms (mean of {} decodes)", each * 1e3, rounds * streams.len());
}
