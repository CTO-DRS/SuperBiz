#!/usr/bin/env python3
# [P18] merge_p18_strings.py — merge 18-c.txt (st2_) + 18-c-settings.txt (st3_) into strings.xml (ar/en)
import re, sys, io

REPO = "/home/z/my-project/SuperBiz"
FRAGS = [
    f"{REPO}/scripts/p18_strings/18-c.txt",
    f"{REPO}/scripts/p18_strings/18-c-settings.txt",
    f"{REPO}/scripts/p18_strings/19-verify.txt",
]
FILES = {
    "ar": f"{REPO}/app/src/main/res/values/strings.xml",
    "en": f"{REPO}/app/src/main/res/values-en/strings.xml",
}

def xml_escape(s: str) -> str:
    # AAPT2 rules: apostrophes/double quotes are quoting chars in string resources and must be escaped
    s = s.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"')
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

def load_frags():
    entries = []  # (key, ar, en)
    seen = set()
    for path in FRAGS:
        with io.open(path, encoding="utf-8") as f:
            for ln, raw in enumerate(f, 1):
                raw = raw.rstrip("\n")
                if not raw.strip():
                    continue
                parts = raw.split("|")
                if len(parts) != 3:
                    sys.exit(f"FATAL {path}:{ln}: expected 3 segments, got {len(parts)}: {raw[:80]}")
                key, ar, en = parts
                if not re.fullmatch(r"[a-z][a-z0-9_]*", key):
                    sys.exit(f"FATAL {path}:{ln}: bad key '{key}'")
                if key in seen:
                    sys.exit(f"FATAL {path}:{ln}: duplicate key '{key}'")
                seen.add(key)
                entries.append((key, ar, en))
    return entries

def existing_keys(path):
    return set(re.findall(r'<string name="([^"]+)"', io.open(path, encoding="utf-8").read()))

def inject(path, lang, entries):
    text = io.open(path, encoding="utf-8").read()
    block = "\n    <!-- [P18] v2.0 statement system strings (merged from p18_strings) -->\n"
    for key, ar, en in entries:
        val = xml_escape(ar if lang == "ar" else en)
        block += f'    <string name="{key}">{val}</string>\n'
    idx = text.rfind("</resources>")
    if idx < 0:
        sys.exit(f"FATAL {path}: no </resources>")
    text = text[:idx] + block + text[idx:]
    io.open(path, "w", encoding="utf-8").write(text)

def main():
    entries = load_frags()
    print(f"frag entries: {len(entries)} (st2 + st3 + st4)")
    for lang, path in FILES.items():
        have = existing_keys(path)
        fresh = [(k, ar, en) for (k, ar, en) in entries if k not in have]
        skipped = len(entries) - len(fresh)
        if fresh:
            inject(path, lang, fresh)
        print(f"merged -> {path} (+{len(fresh)}, skipped {skipped} already present)")
    print("OK")

if __name__ == "__main__":
    main()
