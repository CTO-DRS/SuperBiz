#!/usr/bin/env python3
# [P16] merge_p16_strings.py — 合并 16-a.txt + 16-b.txt + 16-c.txt 到 strings.xml (ar/en)
import re, sys, io

REPO = "/home/z/my-project/SuperBiz"
FRAGS = [f"{REPO}/scripts/p16_strings/16-a.txt", f"{REPO}/scripts/p16_strings/16-b.txt", f"{REPO}/scripts/p16_strings/16-c.txt"]
FILES = {
    "ar": f"{REPO}/app/src/main/res/values/strings.xml",
    "en": f"{REPO}/app/src/main/res/values-en/strings.xml",
}

def xml_escape(s: str) -> str:
    # AAPT2 规则：撇号/双引号在字符串资源中是引号字符，必须转义（曾因（同因） don't 报 Invalid unicode escape）
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
    block = "\n    <!-- [P16] v1.5 strings (merged from p16_strings) -->\n"
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
    print(f"loaded {len(entries)} keys from fragments")
    for lang, path in FILES.items():
        have = existing_keys(path)
        clash = [k for k, _, _ in entries if k in have]
        if clash:
            sys.exit(f"FATAL {path}: keys already exist: {clash}")
        inject(path, lang, entries)
        # 复检
        have2 = existing_keys(path)
        missing = [k for k, _, _ in entries if k not in have2]
        if missing:
            sys.exit(f"FATAL {path}: injection failed for {missing}")
        print(f"{lang}: injected {len(entries)} keys -> {path}")
    # 占位符一致性校验 ar/en
    for key, ar, en in entries:
        pa = sorted(re.findall(r"%\d+\$[sd]", ar))
        pe = sorted(re.findall(r"%\d+\$[sd]", en))
        if pa != pe:
            sys.exit(f"FATAL placeholder mismatch for '{key}': ar={pa} en={pe}")
    print("placeholder parity OK; all checks passed")

if __name__ == "__main__":
    main()
