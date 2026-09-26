You are the IMPLEMENTER in a clean-room test. Write a throwaway Python 3 decoder for the "WI" portrait codec using ONLY the written specification, then check it against published checkpoint hashes. Your purpose is to find any gap, ambiguity or error in the specification text.

STRICT clean-room rules. You may read ONLY these files:
- <repo>/spec/wi-codec.md (the specification)
- <repo>/spec/test-vectors/wi-checkpoints.json (expected hashes)
- <repo>/spec/portrait.md (background only)
Do NOT open, grep, list or run anything under <repo>/native/, python/, third_party/, decoder/, portrait/, build/, or <scratch>/cleanroom/analyst/. Do not look for or use any existing WI decoder, library or source on this machine or online. Don't search the web for the codec either.

Inputs: six WI streams at <scratch>/cleanroom/blind/1.wi ... 6.wi (index = the JSON "index").
Write your code only in <scratch>/cleanroom/blind/ (for example impl.py). Standard library only (hashlib, struct).

Privacy (strict): the inputs are real people's portraits. Never print, save or return pixel values, array contents or images. Print only SHA-256 hashes, counts, sizes and match/mismatch results. Do not write any image files. Do not create files outside the blind/ directory.

Task:
1. Implement the decoder exactly as the spec says, including every checkpoint of its section 14 (C1 to C8, and the scalars q, ll_bits, skip, escape_bits, coefficient counts, bytes_consumed), serialised exactly as the spec defines.
2. Compare every checkpoint and scalar with wi-checkpoints.json for all six vectors. When something mismatches, debug it by re-reading the spec, not by guessing from other sources. Note what you had to change and why.
3. Report back: (a) a table of which checkpoints match for each vector; (b) EVERY place where the spec was ambiguous, incomplete, misleading or wrong, with the section number, what you first did, and what turned out to be right (if you found out); (c) anything you had to assume. Be specific and exhaustive in (b) even for small things: this is the main output. Put your findings in your final message; do not write report files.
