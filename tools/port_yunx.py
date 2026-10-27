"""Copy YunX (CYQawa/YunX, AGPL-3.0) Kotlin sources into the cloud module and rewrite the
JVM/Android imports the compat layer covers. Anything left over is listed for manual porting.

usage: python port_yunx.py <yunx-checkout> <glob-relative-to-com/yunx/app> [...]
"""
import fnmatch
import pathlib
import re
import sys

COMPAT = "com.linroid.ketch.cloud.compat"
IMPORT_MAP = [
    (r"^import org\.json\.", f"import {COMPAT}.json."),
    (r"^import okhttp3\.", f"import {COMPAT}.http."),
    (r"^import java\.net\.URLEncoder$", f"import {COMPAT}.util.URLEncoder"),
    (r"^import java\.net\.URLDecoder$", f"import {COMPAT}.util.URLDecoder"),
    (r"^import java\.security\.MessageDigest$", f"import {COMPAT}.crypto.MessageDigest"),
    (r"^import javax\.crypto\.Cipher$", f"import {COMPAT}.crypto.Cipher"),
    (r"^import javax\.crypto\.spec\.SecretKeySpec$", f"import {COMPAT}.crypto.SecretKeySpec"),
    (r"^import javax\.crypto\.spec\.IvParameterSpec$", f"import {COMPAT}.crypto.IvParameterSpec"),
    (r"^import android\.util\.Base64$", f"import {COMPAT}.util.AndroidBase64 as Base64"),
    (r"^import android\.util\.Log$", f"import {COMPAT}.util.Log"),
    (r"^import java\.util\.concurrent\.TimeUnit$", f"import {COMPAT}.http.TimeUnit"),
    (r"^import java\.io\.IOException$", f"import {COMPAT}.http.IOException"),
    (r"^import java\.util\.concurrent\.ConcurrentHashMap$", f"import {COMPAT}.util.ConcurrentHashMap"),
    (r"^import java\.util\.concurrent\.ThreadLocalRandom$", f"import {COMPAT}.util.ThreadLocalRandom"),
    (r"^import java\.security\.SecureRandom$", f"import {COMPAT}.util.SecureRandom"),
]
BODY_MAP = [
    (r"\bokhttp3\.", f"{COMPAT}.http."),
    (r"\bjava\.security\.MessageDigest\b", f"{COMPAT}.crypto.MessageDigest"),
    (r"\bsynchronized\(", "syncLock("),
    (r"\bSystem\.currentTimeMillis\(\)", "currentTimeMillis()"),
    (r"\bSystem\.nanoTime\(\)", "nanoTime()"),
    (r"\bThread\.sleep\(", "sleepBlocking("),
]
# Names the rewrites above introduce; each gets an import when the file uses it.
AUTO_IMPORTS = {
    "syncLock(": "syncLock",
    "currentTimeMillis()": "currentTimeMillis",
    "nanoTime()": "nanoTime",
    "sleepBlocking(": "sleepBlocking",
    "Dispatchers.IO": "IO",
}
# Files ported by hand because they use platform APIs; the script never overwrites them.
MANUAL = {
    "data/network/HttpClients.kt",
    "util/DiagnosticLog.kt",
    "util/LogRedactor.kt",
}
# Imports the common source set needs that the Android original got implicitly.
EXTRA_IMPORTS = {
    "@Volatile": "import kotlin.concurrent.Volatile",
}
LEFTOVER = re.compile(r"^import (java|javax|android|androidx|org\.(?!jetbrains)|com\.squareup|net\.)[\w.]*", re.M)
NL = chr(10)


def port(text: str) -> str:
    for pattern, repl in IMPORT_MAP:
        text = re.sub(pattern, repl, text, flags=re.M)
    for pattern, repl in BODY_MAP:
        text = re.sub(pattern, repl, text)
    needed = [
        f"import {COMPAT}.util.{name}"
        for token, name in AUTO_IMPORTS.items()
        if token in text and f"import {COMPAT}.util.{name}" not in text
    ]
    needed += [imp for token, imp in EXTRA_IMPORTS.items() if token in text and imp not in text]
    if needed:
        text = re.sub(
            r"^(package [\w.]+)" + NL,
            lambda m: m.group(1) + NL + NL + NL.join(needed) + NL,
            text,
            count=1,
            flags=re.M,
        )
    return text


def main() -> None:
    checkout = pathlib.Path(sys.argv[1])
    src_root = checkout / "app" / "src" / "main" / "kotlin"
    out_root = (
        pathlib.Path(__file__).resolve().parent.parent
        / "overlay" / "library" / "cloud" / "src" / "commonMain" / "kotlin"
    )
    globs = sys.argv[2:]
    app_root = src_root / "com" / "yunx" / "app"
    all_files = sorted(app_root.rglob("*.kt"))
    chosen = [
        p for p in all_files
        if p.relative_to(app_root).as_posix() not in MANUAL
        and any(fnmatch.fnmatch(p.relative_to(app_root).as_posix(), g) for g in globs)
    ]
    for path in chosen:
        rel = path.relative_to(src_root)
        text = port(path.read_text(encoding="utf-8"))
        target = out_root / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8", newline=NL)
        left = sorted(set(m.group(0) for m in LEFTOVER.finditer(text)))
        print(f"{rel.as_posix()}  {len(text) // 1024}KB  leftover: {', '.join(l[7:] for l in left) or '-'}")


if __name__ == "__main__":
    main()
