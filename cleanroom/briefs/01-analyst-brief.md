You are the ANALYST half of a clean-room reverse-engineering effort. Your job: study a decompiled image codec and write a complete, implementation-neutral FUNCTIONAL SPECIFICATION of it, so that a separate engineer who will never see the codec source can write a bit-exact decoder from your spec alone, in any language.

## Context
Repository: <repo> (an open-source spec + decoders for South African driving-licence barcodes; read README.md, spec/portrait.md and spec/card-barcode.md first for context). The licence card's barcode payload contains a small portrait as a Summus wavelet image ("WI"), 550–603 bytes, decoding to 200 x 250 8-bit greyscale. The only decoder is a C++ reconstruction (decompiled from the original Windows DLL by Donald Jansen, no licence) at native/portrait/swi/SWIDecoder.cpp (12k lines, generated names like sub_10030730) and SWIDecoder.h, wrapped by native/portrait/src/sadl_portrait.cpp (C API: accepts only header 57 49 04 00 fa 00 c8, byte7 in {0x42,0x43}, bytes 9..11 = 28 40 00; calls WiDecompress with Fast=0, Smoothing=0, Sharpening=1; output reversed 180°). We want to replace this unlicensed code with our own implementation, hence the clean room.

Measured with gcov on the six public portraits: only 58 of 189 functions and ~1,400 of 8,745 executable lines run; the largest is sub_10030730 (~512 executed lines). Only that executed path matters. Everything else can be specified as "not used for licence portraits; reject such input".

## Your deliverables
1. **The spec**, written to <repo>/spec/wi-codec.md. If the Write tool refuses that path, write it to <scratch>/cleanroom/wi-codec.txt instead. If both are refused, return the full text inline in your final message. It must cover, precisely enough for bit-exact reimplementation:
   - The container and header: every byte the licence path reads, what each parameter means, and which values licence portraits have.
   - The bit reader: bit order, byte order, refill behaviour, and what happens at end of data.
   - Every entropy-decoding stage: the symbol alphabets, context and model state and its initialisation, update rules, probability or frequency tables with ALL constant tables written out in full, and the order in which coefficients or subbands are visited.
   - Dequantisation and reconstruction of coefficients: the quantiser step sizes per subband and where they come from.
   - The inverse transform: the number of levels, the subband layout and dimensions for 200 x 250 (including how odd sizes are split), the exact filter taps or lifting steps, boundary extension, and the order of row and column passes.
   - The "Sharpening=1" post-processing, and any smoothing that is off but whose absence matters.
   - Final clamping and rounding to 8 bits, and the output raster order before the 180° flip. Our C API reverses it; say that.
   - EXACT integer semantics everywhere: operand widths, signedness, arithmetic versus logical shifts, the rounding of divisions and shifts on negative numbers, and any place where 32-bit wrap-around matters. The C is built with -fwrapv -fno-strict-aliasing; the code was 32-bit x86, and our copy uses uint32_t in two spots for LP64 parity. Call out every such place.
   - Name the standard algorithms when you can identify them (for example "CDF 9/7 lifting", "adaptive binary arithmetic coder", "zerotree/SPIHT-like significance pass"), but specify exactly regardless.
   - A decoding walk-through listing numbered CHECKPOINTS: the stage boundaries at which an implementer can hash an intermediate array. Define each one implementation-neutrally, for example "C3: the 250 x 200 array of reconstructed wavelet coefficients before the inverse transform, row-major, each as a little-endian int32".
2. **Checkpoint hashes**: write <repo>/spec/test-vectors/wi-checkpoints.json. It contains, for each of the six public vectors (index 1–6 as in spec/test-vectors/public-vectors.json), the SHA-256 of each checkpoint array exactly as the spec defines it, plus the WI length and the final portrait hash. The final hash must equal portrait_sha256 in public-vectors.json, which is an end-to-end check of your checkpoint definitions. Compute them from an instrumented COPY of the codec built in your scratch directory <scratch>/cleanroom/analyst/ (create it). Never modify the repository's C/C++ files. Get the WI sections with python/sadl.py and python/public_vectors.py (`sadl.photo_section(sadl.decrypt(raw))`).
3. A final message that summarises the spec: its sections, the checkpoints, any uncertainty, and anything you could not pin down. Say where the files were written.

## Clean-room rules (important: the whole point is that the spec is not a copy)
- Describe WHAT the decoder computes, in your own words, with fresh pseudocode, equations and tables where needed.
- Do NOT paste or transliterate code. Do NOT use the decompiled identifiers (sub_XXXX, T_size_NN, arg_4, v3 ...) or the source comments in the spec.
- Constant tables that are part of the format (quantiser tables, initial probabilities, filter coefficients) must be given in full: they are facts about the format.
- Structure the spec by format and algorithm, not by the code's functions.
- Keep your working notes (function maps, traces) in your scratch directory only. Nothing from them goes into the repository except the spec and the checkpoint JSON.

## Privacy (strict)
The six public vectors (fetched into third_party/, git-ignored) hold what look like real people's details and portraits. Never print, save outside your scratch dir, or put into the repository any decoded field value, any pixel data or any image. Print only hashes, sizes, counts and statistics. Delete dumped intermediate arrays from your scratch directory when you are done, keeping only your notes. Do not open or quote third_party/**/UnitTest1.cs beyond what public_vectors.py does for you.

## Method suggestions
- Build an instrumented copy with -O0 -g plus gcov or printf hashes. Trace the executed call graph from WiDecompress. Name the functions by role in your notes.
- Verify your understanding by writing your OWN throwaway prototype decoder from your spec, in Python, in the scratch dir, and check that it reproduces the checkpoint hashes and the final portrait_sha256 for all six vectors. This is the best proof that the spec is complete. Its code must NOT go into the repository, and it is not a deliverable. The implementer will write their own from the spec.
- If a detail is data-dependent and not exercised by the six vectors, say so explicitly.

Take the time needed; correctness and completeness of the spec matter far more than speed.
