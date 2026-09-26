<!-- The analyst's answer, verbatim (SHA-256 in cleanroom/LOG.md). The hashes it gives are of its delivery; the orchestrator's editorial changes afterwards are logged. -->

The implementer is right on both questions, and the spec is now revision 3. No decoding output changes; the amendment makes two rules explicit and adds four failing vectors.

**New hashes**
- `spec/wi-codec.md`: `95482ba037c52b39818dfa0d08e3e0e596d617299b1770c0493fb5e25817b358` (revision 2 was `1b6aafec…`)
- `spec/test-vectors/wi-synthetic.json`: `3c9a5a7d8d938cb5c1323387023c458f2e4f7a479470883a594ef5906794ee72` (was `278aed6a…`)
- `wi-checkpoints.json` is unchanged.

## Question 1: an over-long skip count cut by end of data

**What the reference does.** When the data runs out partway through an over-long skip count, the reference meets end of data first (E2). Its unary loop then stops, and it also finds the count too large (E3). Its sequence is E2 then E3, and the first error is E2.

**The rule, now in section 7.** Read 1 bits until a 0 bit arrives, however many there are, and compare the count with 5 only after that 0 bit. A decoder must not stop at the sixth 1 bit.
- If the data ends before the 0 bit, the error is E2, even after six or more 1 bits.
- If six or more 1 bits are followed by a 0 bit, the error is E3, whether or not more data follows. Nothing is read after that 0 bit, so a stream that ends right after it is still E3.

**New vectors.** The reference, my prototype and the earlier blind decoder all give the expected error on each:
- `e2-skip-prefix-cut`: 21 1 bits, then end of data. Expected E2; the reference's sequence is E2, E3.
- `e2-skip-5-cut`: five 1 bits, a legal count so far, then end of data. Expected E2.
- `e3-skip-6-at-end`: six 1 bits and the 0 bit, then nothing more. Expected E3; the reference reads nothing after the 0 bit.

The two behaviours differ, so both are covered. The existing `e3-skip-6` and `e3-skip-9` cover six or more 1 bits, a 0 bit, and more data after it (E3).

## Question 2: stopping at the first error

**The rule, now in section 12.** The error to report is the first condition of E2 to E5 met in reading order; E1 is decided before any decoding. A decoder may stop at the first error, and it must not report a later one instead. Stopping early cannot change which error comes first, because up to that point a stopping decoder and the reference take the same steps.

**Where the two behaviours give different codes.** After end of data, the reference reads zeros and can meet a second condition in two ways:
- E3 after E2, when the data ends inside an over-long skip count.
- E5 after E2, when the data ends inside a run, which leaves the run count non-zero.

In both, E2 is the canonical error. After E3, E4 or E5 the reference reads and checks nothing more.

**Evidence.** I made the instrumented reference log every error condition in order. I ran it on all 9,733 truncations of the synthetic streams (every valid one and every E3/E4/E5 one). The only sequence with more than one error was E2 then E5, in 53 cases; E2 then E3 appeared only in the new skip vector.
- On 449 of those truncations (a random 400 plus every multi-error case), my prototype, which stops at the first error, gave the reference's first error every time.
- The reference's API does not distinguish E2 to E5: all four return `SADL_PORTRAIT_DECODE_FAILED`. The codes are the spec's names, identified by the instrumentation. Section 12 now says so.

**Vector that tells the two behaviours apart.** `e2-then-e5-run-cut` ends inside the first run extension. Expected E2; the reference's sequence is E2, E5. `e2-skip-prefix-cut` (E2, E3) also tells them apart.

**Check of `wi-synthetic.json`.** All 26 failing cases follow the first-error rule. For each one the reference's first error, the recorded `expected_error` and my prototype agree. The exception is `e1-byte8-odd`, which the profile rejects as E1 but the current C API passes to the codec, as documented in its note.

## What changed, section by section

**`spec/wi-codec.md`**
- **Header:** the revision note now describes revision 3 and states that no output changes.
- **Section 3 (end of data):** it now names the error E2 and says a decoder may stop there (see section 12). The reference's run-to-the-end behaviour is kept as a parenthetical.
- **Section 7:** the comment now reads "checked only after the 0 bit has been read; else E3". A new paragraph gives the two rules above, notes what the reference does, and names the five vectors that test it.
- **Section 12:** a new "Which error" paragraph covers:
  - the first-error rule, and that a decoder may stop at the first error but must not report a later one;
  - why stopping early cannot change the outcome;
  - the two second conditions the reference can meet;
  - where the synthetic file records the canonical error and the reference's sequence;
  - that the reference's API does not distinguish E2 to E5;
  - the 9,733-stream sweep.
  
  The C API sentence is reworded, and the note on the two checks that cannot trigger is now its own paragraph. The E1 to E5 table is unchanged.
- **Section 14:** "22 streams that must fail" is now "26", described as the canonical (first) error.
- **Sections 0, 1, 2, 4 to 11, 13 and 15:** unchanged.

**`spec/test-vectors/wi-synthetic.json`**
- 67 cases (41 valid, 26 failing).
- The four new failing cases are placed with their groups.
- Every failing case the reference decodes past the header now carries `reference_error_sequence`.
- The `about` text now says revision 3 and defines the canonical error, and `errors.canonical` states the rule.
- The 41 valid cases are unchanged.

Every stream is still synthetic and made by my encoder. The four new streams are cuts of synthetic streams, all under 674 bytes. My scratch code and the blind agent's code stay in the scratch directory.
