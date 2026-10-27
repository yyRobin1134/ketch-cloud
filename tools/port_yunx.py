"""Copy YunX (CYQawa/YunX, AGPL-3.0) Kotlin sources into the cloud module and rewrite the
JVM/Android APIs the compat layer covers. Per-file edits that cannot be generic live in
fixups.py so a port can be repeated against a newer YunX checkout.

usage: python port_yunx.py <yunx-checkout> <glob-relative-to-com/yunx/app> [...]
"""
import fnmatch
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from fixups import FIXUPS  # noqa: E402

COMPAT = "com.linroid.ketch.cloud.compat"
NL = chr(10)


def util(name):
    return f"import {COMPAT}.util.{name}"


IMPORT_MAP = [
    (r"^import org\.json\.", f"import {COMPAT}.json."),
    (r"^import okhttp3\.", f"import {COMPAT}.http."),
    (r"^import java\.net\.URLEncoder$", util("URLEncoder")),
    (r"^import java\.net\.URLDecoder$", util("URLDecoder")),
    (r"^import java\.net\.URI$", util("URI")),
    (r"^import java\.security\.MessageDigest$", f"import {COMPAT}.crypto.MessageDigest"),
    (r"^import javax\.crypto\.Cipher$", f"import {COMPAT}.crypto.Cipher"),
    (r"^import javax\.crypto\.spec\.SecretKeySpec$", f"import {COMPAT}.crypto.SecretKeySpec"),
    (r"^import javax\.crypto\.spec\.IvParameterSpec$", f"import {COMPAT}.crypto.IvParameterSpec"),
    (r"^import android\.util\.Base64$", util("AndroidBase64 as Base64")),
    (r"^import android\.util\.Log$", util("Log")),
    (r"^import android\.content\.Context$", util("Context")),
    (r"^import java\.util\.concurrent\.TimeUnit$", f"import {COMPAT}.http.TimeUnit"),
    (r"^import java\.io\.IOException$", f"import {COMPAT}.http.IOException"),
    (r"^import java\.util\.concurrent\.ConcurrentHashMap$", util("ConcurrentHashMap")),
    (r"^import java\.util\.concurrent\.ThreadLocalRandom$", util("ThreadLocalRandom")),
    (r"^import java\.security\.SecureRandom$", util("SecureRandom")),
    (r"^import java\.util\.zip\.CRC32$", util("CRC32")),
    (r"^import java\.text\.SimpleDateFormat$", util("SimpleDateFormat")),
    (r"^import java\.util\.(Date|Locale|Calendar|TimeZone)$", lambda m: util(m.group(1))),
    (r"^import java\.math\.BigInteger$", util("BigInteger")),
    (r"^import java\.util\.UUID$", util("UUID")),
    (r"^import java\.io\.ByteArrayOutputStream$", util("ByteArrayOutputStream")),
    # Charsets are spelled out by the rewrites below; drop the JVM-only import.
    (r"^import java\.nio\.charset\.StandardCharsets" + NL, ""),
    # Android WebView APIs have no common equivalent; the files that need them are fixed by hand.
    (r"^import (?:androidx\.webkit|android\.webkit)\.[\w.]+" + NL, ""),
]
BODY_MAP = [
    (r"\bokhttp3\.", f"{COMPAT}.http."),
    (r"\borg\.json\.", f"{COMPAT}.json."),
    (r"\bjava\.security\.MessageDigest\b", f"{COMPAT}.crypto.MessageDigest"),
    (r"\bjava\.util\.concurrent\.ConcurrentHashMap\b", f"{COMPAT}.util.ConcurrentHashMap"),
    (r"\bjava\.net\.URLEncoder\b", f"{COMPAT}.util.URLEncoder"),
    (r"\bjava\.net\.URI\b", f"{COMPAT}.util.URI"),
    (r"\bjava\.io\.ByteArrayOutputStream\b", f"{COMPAT}.util.ByteArrayOutputStream"),
    (r"\bandroid\.util\.Base64\b", f"{COMPAT}.util.AndroidBase64"),
    (r"\bsynchronized\(", "syncLock("),
    (r"\bSystem\.currentTimeMillis\(\)", "currentTimeMillis()"),
    (r"\bSystem\.nanoTime\(\)", "nanoTime()"),
    (r"\bSystem\.arraycopy\(", "arraycopy("),
    (r"\bThread\.sleep\(", "sleepBlocking("),
    (r"\bMath\.random\(\)", "kotlin.random.Random.nextDouble()"),
    (r"\.toByteArray\((?:Charsets|StandardCharsets)\.UTF_8\)", ".encodeToByteArray()"),
    (r"\bjava\.net\.URLDecoder\b", f"{COMPAT}.util.URLDecoder"),
    (r"\bjava\.io\.IOException\b", f"{COMPAT}.http.IOException"),
]
# Names the rewrites above introduce; each gets an import when the file uses it.
AUTO_IMPORTS = {
    "syncLock(": "syncLock",
    "currentTimeMillis()": "currentTimeMillis",
    "nanoTime()": "nanoTime",
    "sleepBlocking(": "sleepBlocking",
    "Dispatchers.IO": "IO",
    ".format(": "format",
    "gunzip(": "gunzip",
    "arraycopy(": "arraycopy",
    ".toByteArray(": "toByteArray",
}
# Files ported by hand because they use platform APIs; the script never overwrites them.
MANUAL = {
    "data/network/HttpClients.kt",
    "data/network/video/HeadlessWebView.kt",
    "data/security/CredentialCipher.kt",
    "util/DiagnosticLog.kt",
    "util/LogRedactor.kt",
    "data/db/AppDatabase.kt",
    "data/db/AccountDao.kt",
}
# Not ported at all: OkHttp interceptors have no counterpart in the compat client.
SKIP = {
    "data/network/DiagnosticNetworkInterceptor.kt",
}
# Imports the common source set needs that the Android original got implicitly.
EXTRA_IMPORTS = {
    "@Volatile": "import kotlin.concurrent.Volatile",
}
LEFTOVER = re.compile(
    r"^import (java|javax|android|androidx|org\.(?!jetbrains)|com\.squareup|net\.)[\w.]*", re.M
)


def rewrite_string_constructor(text: str) -> str:
    """`String(bytes, Charsets.UTF_8)` -> `(bytes).decodeToString()`, matching parentheses."""
    out = []
    i = 0
    marker = "String("
    while True:
        j = text.find(marker, i)
        if j < 0:
            out.append(text[i:])
            break
        # Only the constructor call: not `toString(`, `ByteString(` and the like.
        if j > 0 and (text[j - 1].isalnum() or text[j - 1] in "_."):
            out.append(text[i:j + len(marker)])
            i = j + len(marker)
            continue
        depth = 1
        k = j + len(marker)
        while k < len(text) and depth:
            depth += {"(": 1, ")": -1}.get(text[k], 0)
            k += 1
        inner = text[j + len(marker):k - 1]
        m = re.search(r",\s*(?:Charsets|StandardCharsets)\.UTF_8\s*$", inner)
        if depth == 0 and m:
            out.append(text[i:j])
            out.append("(" + inner[:m.start()].rstrip() + ").decodeToString()")
            i = k
        else:
            out.append(text[i:j + len(marker)])
            i = j + len(marker)
    return "".join(out)


def rewrite_dot_all(text: str) -> str:
    """`Regex(p, RegexOption.DOT_MATCHES_ALL)` -> `Regex("(?s)" + (p))`; the option is JVM-only."""
    marker = "Regex("
    out = []
    i = 0
    while True:
        j = text.find(marker, i)
        if j < 0:
            out.append(text[i:])
            break
        depth = 1
        k = j + len(marker)
        in_raw = False
        while k < len(text) and depth:
            if text.startswith('"""', k):
                in_raw = not in_raw
                k += 3
                continue
            if not in_raw:
                depth += {"(": 1, ")": -1}.get(text[k], 0)
            k += 1
        inner = text[j + len(marker):k - 1]
        m = re.search(r",\s*RegexOption\.DOT_MATCHES_ALL\s*$", inner)
        if depth == 0 and m:
            out.append(text[i:j])
            out.append('Regex("(?s)" + (' + inner[:m.start()].rstrip() + "))")
            i = k
        else:
            out.append(text[i:j + len(marker)])
            i = j + len(marker)
    return "".join(out)


def remove_decl(text: str, name: str, rel: str) -> str:
    """Drops a `const val` / `var` / `val` declaration, its annotations and KDoc, up to the next blank line."""
    pattern = r"^[ \t]*(?:@\w+[ \t]*" + NL + r"[ \t]*)*(?:private |internal )?(?:const )?(?:val|var) " + re.escape(name) + r"\b"
    m = re.search(pattern, text, re.M)
    if not m:
        print(f"  !! declaration not found in {rel}: {name}")
        return text
    start = m.start()
    doc = re.search(r"/\*\*(?:(?!\*/).)*\*/[ \t]*" + NL + r"\Z", text[:start], re.S)
    if doc:
        start = doc.start()
    end = text.find(NL + NL, m.end())
    end = len(text) if end < 0 else end + 2
    return text[:start] + text[end:]


def remove_expr_function(text: str, name: str, rel: str) -> str:
    """Drops an expression-bodied `fun name(...) = ...` up to the next blank line, with its KDoc."""
    pattern = r"^[ \t]*(?:(?:private|internal|public|suspend|inline|override)\s+)*fun\s+" + re.escape(name) + r"\b"
    m = re.search(pattern, text, re.M)
    if not m:
        print(f"  !! function not found in {rel}: {name}")
        return text
    start = m.start()
    doc = re.search(r"/\*\*(?:(?!\*/).)*\*/[ \t]*" + NL + r"\Z", text[:start], re.S)
    if doc:
        start = doc.start()
    blank = text.find(NL + NL, m.end())
    closing = text.find(NL + "}", m.end())  # the enclosing class ends right after the function
    if closing >= 0 and (blank < 0 or closing < blank):
        return text[:start].rstrip(" " + NL) + NL + text[closing:]
    end = len(text) if blank < 0 else blank + 2
    return text[:start] + text[end:]


def remove_function(text: str, name: str, rel: str) -> str:
    """Drops `fun name(...) { ... }` together with the KDoc comment right above it."""
    pattern = r"^[ \t]*(?:(?:private|internal|public|suspend|inline|override)\s+)*fun\s+" + re.escape(name) + r"\b"
    m = re.search(pattern, text, re.M)
    if not m:
        print(f"  !! function not found in {rel}: {name}")
        return text
    start = m.start()
    doc = re.search(r"/\*\*(?:(?!\*/).)*\*/[ \t]*" + NL + r"\Z", text[:start], re.S)
    if doc:
        start = doc.start()
    brace = text.find("{", m.end())
    depth = 0
    k = brace
    while k < len(text):
        depth += {"{": 1, "}": -1}.get(text[k], 0)
        k += 1
        if depth == 0:
            break
    while k < len(text) and text[k] == NL:
        k += 1
    return text[:start] + text[k:]


def strip_room(text: str) -> str:
    """Room entity -> plain `@Serializable` data class, so rows can be stored as JSON."""
    text = re.sub(r"^import androidx\.room\.[\w.]+" + NL, "", text, flags=re.M)
    text = re.sub(r"^@Entity\(.*\)" + NL, "", text, flags=re.M)
    text = re.sub(r"^[ \t]*@(?:PrimaryKey|ColumnInfo)(?:\(.*\))?" + NL, "", text, flags=re.M)
    text = re.sub(r"^(data class )", "@Serializable" + NL + r"\1", text, count=1, flags=re.M)
    return re.sub(
        r"^(package [\w.]+)" + NL,
        lambda m: m.group(1) + NL + NL + "import kotlinx.serialization.Serializable" + NL,
        text,
        count=1,
        flags=re.M,
    )


def port(text: str, rel: str) -> str:
    if re.fullmatch(r"data/db/\w+AccountEntity\.kt", rel):
        text = strip_room(text)
    for pattern, repl in IMPORT_MAP:
        text = re.sub(pattern, repl, text, flags=re.M)
    for pattern, repl in BODY_MAP:
        text = re.sub(pattern, repl, text)
    text = rewrite_string_constructor(text)
    text = rewrite_dot_all(text)
    for old, new in FIXUPS.get(rel, []):
        if old == "__remove_fun__":
            text = remove_function(text, new, rel)
            continue
        if old == "__remove_decl__":
            text = remove_decl(text, new, rel)
            continue
        if old == "__remove_expr_fun__":
            text = remove_expr_function(text, new, rel)
            continue
        if old.startswith("re:"):
            text, count = re.subn(old[3:], new, text, flags=re.S)
            if not count:
                print(f"  !! regex fixup not applied in {rel}: {old[:60]!r}")
            continue
        if old not in text:
            print(f"  !! fixup not applied in {rel}: {old[:60]!r}")
        text = text.replace(old, new)
    needed = [
        util(name)
        for token, name in AUTO_IMPORTS.items()
        if token in text and util(name) not in text
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
    chosen = [
        p for p in sorted(app_root.rglob("*.kt"))
        if p.relative_to(app_root).as_posix() not in MANUAL | SKIP
        and any(fnmatch.fnmatch(p.relative_to(app_root).as_posix(), g) for g in globs)
    ]
    for path in chosen:
        rel_app = path.relative_to(app_root).as_posix()
        rel = path.relative_to(src_root)
        text = port(path.read_text(encoding="utf-8"), rel_app)
        target = out_root / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8", newline=NL)
        left = sorted(set(m.group(0) for m in LEFTOVER.finditer(text)))
        print(f"{rel.as_posix()}  {len(text) // 1024}KB  leftover: {', '.join(l[7:] for l in left) or '-'}")


if __name__ == "__main__":
    main()
