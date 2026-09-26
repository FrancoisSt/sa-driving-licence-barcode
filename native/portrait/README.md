# sadl-portrait

The WI portrait codec of [../../spec/wi-codec.md](../../spec/wi-codec.md) in safe Rust (`#![deny(unsafe_code)]`;
only the small `ffi` module uses `unsafe`), with no runtime dependencies and the C API of
[include/sadl_portrait.h](include/sadl_portrait.h). It keeps no global state, so it may be called from any number
of threads, and it never panics: every failure is a returned `WiError` (E1 to E5) or status.

```sh
cargo build --release    # target/release/libsadl_portrait.so (.dylib, sadl_portrait.dll) and the static library
cargo test               # every checkpoint of the six public vectors (when fetched), wi-synthetic.json,
                         # the header gate, 100,000 random and mutated inputs, and the C API
SADL_REQUIRE_VECTORS=1 cargo test    # fail instead of skipping when third_party/ is missing
```

```rust
let portrait = sadl_portrait::decode(photo_section)?;             // upright, 50,000 bytes (C8)
let raster = sadl_portrait::decode_native_order(photo_section)?;  // codec order, upside down (C7)
```

## Using it in your project

It is not on crates.io. From Rust, add a git dependency (Cargo finds the crate inside the repository):

```toml
[dependencies]
sadl-portrait = { git = "https://github.com/FrancoisSt/sa-driving-licence-barcode" }
```

From C or any language with an FFI: build the library (`cargo build --release`, adding `--target <triple>` to
cross-compile; for Android, `cargo-ndk` is the usual tool), link `libsadl_portrait` (shared) or the static library,
and include [include/sadl_portrait.h](include/sadl_portrait.h).

From C: `sadl_portrait_decode(wi, size, out)` returns `SADL_PORTRAIT_OK` (0) and fills the 50,000-byte `out`, or
`SADL_PORTRAIT_UNSUPPORTED` (E1), `SADL_PORTRAIT_DECODE_FAILED` (E2 to E5) or `SADL_PORTRAIT_BAD_ARGUMENT` (a null
pointer), leaving `out` untouched. python/sadl.py finds the release library automatically when asked to use it.
