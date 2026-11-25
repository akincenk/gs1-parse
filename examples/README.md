# Examples

`receiving-dock-scans.txt` is a made-up scan log from a receiving dock: two pallet SSCCs,
case labels with batch, best-before date and catch weight, a GS1 DataMatrix, a GS1 QR code,
and some typical bad scans (damaged check digit, 31 February, a plain Code 128 label, a scanner
that sends `^` instead of GS, a scanner that dropped the separator after a batch number, a GS1 DataMatrix
where the separator after the serial number was lost).
All GTINs and SSCCs use the GS1 example company prefixes 0614141 and 9506000.

The GS separators in the file are real ASCII 29 characters. `cat -v` shows them as `^]`.

The case label with content (02) and count (37) is reported as invalid: in the GS1 rules (02) needs
(37), and (37) needs the pallet's SSCC (00). On a real label the SSCC is a separate barcode.
Each line is checked on its own, so use `--no-associations` for a log where one item's barcodes
are on separate lines.

```sh
gs1-parse --today 2026-11-21 < examples/receiving-dock-scans.txt
gs1-parse --json --today 2026-11-21 < examples/receiving-dock-scans.txt
gs1-parse --no-associations --today 2026-11-21 < examples/receiving-dock-scans.txt
```
