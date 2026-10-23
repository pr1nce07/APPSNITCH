# :report (M16)

*Android*

PDF export (PS-01 stretch 14).

## Public API
| Type | Role |
|---|---|
| `ReportLayout` | **pure**: blocks and pagination |
| `Block`, `Page` | drawable elements, no Canvas or Paint |
| `PdfReportWriter` | draws the blocks with `PdfDocument`; shares via FileProvider |

## Design notes
The split is the point: pagination is where the off-by-one errors live, so it is
pure Kotlin and unit-tested without a `PdfDocument`. `PdfReportWriter` draws and
decides nothing.

A block taller than a page gets a page of its own rather than looping forever,
and an empty scan still produces one page — some viewers refuse to open a
zero-page PDF.

Band colours are chosen to stay distinguishable in greyscale; a printed report
where every band looks the same is useless.

The report is written to the app's cache and shared only through `FileProvider`.
It is never uploaded.

## Tests
```bash
./gradlew :report:testDebugUnitTest
```
