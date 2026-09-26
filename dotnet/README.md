# Sadl: South African driving-licence barcodes for .NET

Reads the PDF417 barcode of a South African driving licence (the card back, and the paper temporary licence) from
the barcode reader's **raw bytes**, and decodes the card's 200 x 250 portrait. Managed code only, no native library,
no dependencies. Targets .NET Standard 2.1 and .NET 8.

```csharp
using Sadl;

DecodedBarcode decoded = LicenceBarcode.Decode(rawBytes);   // throws LicenceBarcodeException on a misread
switch (decoded)
{
    case DecodedBarcode.Card card:
        var number = card.Licence.LicenceNumber;  // ValidTo, Codes, PrdpCategories, Photo, ...
        byte[] portrait = WiPortrait.Decode(card.Licence.Photo!);  // 50,000 bytes, row by row, upright
        break;
    case DecodedBarcode.Temporary temporary:
        var issued = temporary.Licence.IssueDate;
        break;
}

var findings = LicenceChecks.Check(decoded);      // spec/checks.md; Finding.IsBlocking()
string json = CanonicalJson.Of(decoded);          // spec/output-format.md
```

## Using it in your project

It is not on NuGet. Either reference the project directly:

```sh
dotnet add reference path/to/sa-driving-licence-barcode/dotnet/src/Sadl/Sadl.csproj
```

or build a package and install it from a local folder:

```sh
dotnet pack dotnet/src/Sadl -c Release -o ./packages
dotnet add package Sadl --source ./packages
```

## What is in it

- `LicenceBarcode.Identify`, `Decrypt` (textbook RSA with the published public keys, unsigned big-endian
  `BigInteger`, `ModPow`), `ParseCard`, `ParseTemporaryLicence` and `Decode` follow spec/card-barcode.md and
  spec/temporary-licence.md.
- `WiPortrait.Decode` / `DecodeNativeOrder` implement spec/wi-codec.md; errors are `WiPortraitException` with
  `Error` (`WiError`) and `Code` ("E1" to "E5").
- Exceptions carry a canonical reason and no data; `ToString()` of the results leaves out every personal value.
- `CanonicalJson` uses System.Text.Json on .NET 8 and a small built-in writer on .NET Standard 2.1.

The decoded fields and the portrait are personal information: do not log, store or upload them without a reason.

Tests: `dotnet test dotnet/Sadl.sln` Without the public vectors (`scripts/fetch-test-vectors.sh`), the tests
that need them pass without checking anything; set `SADL_REQUIRE_VECTORS=1` to make them fail instead.
