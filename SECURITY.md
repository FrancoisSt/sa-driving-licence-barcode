# Security

## Reporting a problem

Report security problems privately, through GitHub's
[private vulnerability reporting](https://github.com/FrancoisSt/sa-driving-licence-barcode/security/advisories/new)
for this repository, not in a public issue. You should get a reply within a week.

**Never include real licence data in a report:** no barcode bytes, images, decoded values or portraits from a real
card, including your own. Describe the input instead (its length, the version bytes, which step fails), or build a
synthetic reproduction, as [spec/test-vectors/](spec/test-vectors/) does.

## What counts

- A decoder in this repository crashing, hanging, reading or writing out of bounds, or using unbounded memory on
  any input. The portrait decoders promise to fail cleanly (errors E1 to E5) on every input; the Rust C API promises
  never to write to `out` on failure.
- A decoder, the demo or the documentation leaking personal data: in logs, exception messages, `toString()`, crash
  reports, backups or files.
- A specification error that makes a misread or tampered barcode pass as a valid licence.

## What does not

- **The public keys are public.** Anyone can decrypt a licence barcode. The encryption stops people from *making*
  a barcode, not from reading one; a decoded barcode proves only that the issuer made it, not that the person
  holding the card is its owner. See [spec/checks.md](spec/checks.md) and [docs/privacy.md](docs/privacy.md).
- Weaknesses of the licence card or its issuing system itself: report those to the issuer.

## Supported versions

Only the latest commit on `main`.
