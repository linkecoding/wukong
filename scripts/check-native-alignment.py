#!/usr/bin/env python3
"""Check APK 64-bit ELF LOAD/RELRO and uncompressed JNI ZIP alignment for 16 KB.

Static check only; also exercise the app on a 16 KB Android device.
https://developer.android.com/guide/practices/page-sizes
"""
import argparse
from pathlib import Path
import struct
import sys
import zipfile

PAGE = 16384


def check(apk):
    failures = []
    count = 0
    with zipfile.ZipFile(apk) as archive, open(apk, "rb") as raw:
        for entry in archive.infolist():
            if not entry.filename.startswith("lib/") or not entry.filename.endswith(".so"):
                continue
            data = archive.read(entry)
            if data[:4] != b"\x7fELF":
                failures.append(entry.filename + ": invalid ELF")
                continue
            if data[4] != 2:  # 16 KB Android concerns 64-bit ABIs.
                continue
            count += 1
            endian = "<" if data[5] == 1 else ">"
            phoff = struct.unpack_from(endian + "Q", data, 32)[0]
            size, number = struct.unpack_from(endian + "HH", data, 54)
            headers = [struct.unpack_from(endian + "IIQQQQQQ", data, phoff + i * size) for i in range(number)]
            loads = [h for h in headers if h[0] == 1]
            issues = []
            for h in loads:
                if h[7] < PAGE or (h[2] - h[3]) % PAGE:
                    issues.append("LOAD alignment")
            for h in headers:
                if h[0] != 0x6474E552:  # PT_GNU_RELRO
                    continue
                end = h[3] + h[6]
                # A RELRO ending at its containing LOAD boundary is compliant too.
                whole_end = any(p[3] <= h[3] and end == p[3] + p[6] for p in loads)
                if end % PAGE and not whole_end:
                    issues.append("RELRO end alignment")
            if entry.compress_type == zipfile.ZIP_STORED:
                raw.seek(entry.header_offset)
                local = raw.read(30)
                name_len, extra_len = struct.unpack_from("<HH", local, 26)
                offset = entry.header_offset + 30 + name_len + extra_len
                if offset % PAGE:
                    issues.append("ZIP data alignment")
            if issues:
                failures.append(entry.filename + ": " + ", ".join(issues))
            else:
                print("PASS", entry.filename)
    for failure in failures:
        print("FAIL", failure, file=sys.stderr)
    print(f"{count} 64-bit libraries checked; {len(failures)} failures")
    return 1 if failures or not count else 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    sys.exit(check(parser.parse_args().apk))
