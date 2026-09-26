# Reading the barcode: cameras, scans and photos

Decoding the bytes is the easy part. Getting a reliable read of a worn, laminated card from a hand-held phone is
where most of the work is. These are the lessons, from real cards on real (including cheap, rugged) phones.

## Which barcode reader

| Reader | Licence card (mirrored PDF417) | Notes |
|---|---|---|
| [zxing-cpp](https://github.com/zxing-cpp/zxing-cpp) (Apache-2.0) | Yes, since PR #175 (November 2020) | Open source, every platform: C++, Android, iOS, Python, .NET, WebAssembly. Gives raw bytes. Reports symbols it found but could not correct (`returnErrors`), with corners |
| Google ML Kit barcode scanning (bundled model) | Yes | Android and iOS, offline, free, closed source ([terms](https://developers.google.com/ml-kit/terms)). Gives raw bytes (`Barcode.rawBytes`). Reads worn cards and cards in plastic sleeves that zxing-cpp finds but cannot correct |
| ZXing (Java), Apple Vision, commercial SDKs | Not tested here | Check on a real card before relying on one: the mirrored symbol and raw-byte access are the two things to confirm |

**Best results: both.** ML Kit first on each frame, zxing-cpp as the fallback. The demo app does this
([../demo](../demo)). Either alone works on most cards.

Whatever the reader: take its **raw bytes**, never its text. A card read is exactly 720 bytes.

## Live camera

- **Resolution over frame rate.** Ask for the sensor's highest analysis resolution (about 4000 × 3000, or what
  the phone offers). At 1920 × 1440, a barcode that filled the guide on screen was only about 800 pixels wide in
  the frame, and neither engine could read a worn card. On Android CameraX:
  `ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE` with a 4:3 aspect ratio.
- **Show the whole analysed frame.** Use the same aspect ratio (4:3) for preview and analysis, and letterbox the
  preview (CameraX `PreviewView.ScaleType.FIT_CENTER`). With a "fill" preview on a tall phone, the screen showed
  only the middle 60% of the frame, so people framed the barcode "perfectly" while it was small in the frame.
- **Decode only the guide.** Crop each frame to the on-screen guide (mapped through the letterbox and the sensor
  rotation), with a 5% margin: ML Kit needs white space around the symbol. That keeps a read to 50 to 800 ms even on
  slow phones.
- **Never wait long for one engine.** ML Kit can spend seconds on a symbol it cannot read (a vehicle licence disc
  behind a windscreen kept it busy 5 s per frame). Wait at most about 1.5 s; while it is still busy, give frames to
  the other engine, and collect its late answer when it arrives.
- **Recover symbols that are found but not read.** When zxing-cpp finds a PDF417 but its error correction fails,
  warp the symbol to a true rectangle using the corners it reports, apply a **vertical box blur** of about 1/60 of
  the symbol's height (along the bars: it removes speckle without blurring the bar widths that carry the data), and
  read it again. Skip this on devices with little memory.
- **One analysis thread; stop at the first good read.** Decide on the analysis thread: a read that decrypts and
  passes the block markers is final (use an atomic flag so that a second frame cannot also be accepted). Always
  close each frame, and catch errors per frame so one bad frame never ends the scan.
- **Show what is happening.** A box that follows the symbol's corners, amber while found-but-not-read and green on
  a read, tells people to hold still rather than move. Slow phones read one or two frames a second: hold a state
  on screen for a second or so, so it does not flicker.
- **Torch: manual.** The laminate reflects a torch; let people turn it on when the room is dark, and suggest
  tilting the card away from glare.
- **Protect the screen.** The result is personal data: on Android set `FLAG_SECURE` so it stays out of
  screenshots, screen recordings and the recent-apps thumbnail.

What people should do: fill the guide with the barcode (not the whole card), keep it level, tilt away from glare.
Code 39 or other barcodes on the card are not the licence barcode.

## Scans and photos (image files)

From a study of 730 uploaded licence images (scans and phone photos, not a live camera):

| | |
|---|---|
| Card backs decoded | 38 of about 50 that showed the back |
| Temporary licences decoded | scans 42 of 69; photos of the whole A4 page 9 of 45 |
| Why reads failed | mostly 200 dpi scans: a real-size card at 200 dpi gives a barcode about 600 px wide, too small. Also fax-style bilevel scans, faint photocopies, blurred photos |
| Size | shrinking decodable barcodes to 1,200 px wide lost more than half; at 480 px none decoded |
| Speed | a median of 0.7 s per upload on a server CPU, including rendering the page |

On a whole A4 scan, zxing usually *finds* the symbol but fails its error correction; the same symbol reads from a
tight crop. The crop reader that recovered most of them:

1. **Find candidates.** On a greyscale copy up to 1,200 px, take the Canny edge density and threshold it. Keep
   blocks with an aspect ratio between 1.8 and 9 (this symbol is about 4.3 times as wide as it is high). Also use
   the corner points of any failed zxing read.
2. **Crop each candidate** from the full-resolution image as a rotated rectangle, deskewed so its long side is
   horizontal, with a 5% margin.
3. **Try recipes on the crop**, in order: as it is; 2× upscale with a vertical box blur of 3 px; the same with
   5 px; 1.5× with 5 px; sharpen; then Otsu or percentile thresholds. Try the crop turned 180° too.

Crops decoded 8 card backs where reading the whole page decoded 3. For a PDF, extract the embedded image at its
original resolution (for example `pdfimages -j`), do not render the page at screen resolution.

## Checklist for a new platform

- [ ] Raw bytes from the reader, 720 for a card.
- [ ] The mirrored symbol reads (test with a real card; the public vectors are bytes, not images).
- [ ] Analysis at the highest resolution, same aspect ratio as the preview, preview letterboxed.
- [ ] Crop to the guide with a margin.
- [ ] No engine can stall the scan.
- [ ] Stop at the first read that passes the block markers.
- [ ] Nothing personal in logs, crash reports or analytics.
