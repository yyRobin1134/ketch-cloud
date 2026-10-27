"""Per-file edits applied after the generic rewrites in port_yunx.py.

Each entry is (old, new) for an exact replacement, or ("__remove_fun__", name) to drop a
function together with its KDoc. A fixup that no longer matches prints a warning, which is how
a YunX update that touched these spots is noticed.
"""

FIXUPS = {
    # File-based containment check; the cloud module never touches the file system.
    "data/download/DownloadPathPolicy.kt": [
        ("import java.io.File\n", ""),
        ("__remove_fun__", "isContained"),
    ],
    "data/network/C139Api.kt": [
        ("import java.io.ByteArrayInputStream\n", ""),
        ("import java.util.zip.GZIPInputStream\n", ""),
        ("d = GZIPInputStream(ByteArrayInputStream(d)).use { it.readBytes() }", "d = gunzip(d)"),
    ],
    "data/network/LanzouApi.kt": [
        ("val u = String(q)", "val u = q.concatToString()"),
    ],
    "data/network/Pan123Api.kt": [
        ("java.lang.Long.toHexString(crc.value and 0xFFFFFFFFL)", "(crc.value and 0xFFFFFFFFL).toString(16)"),
    ],
    "data/network/XunleiApi.kt": [
        (
            r"re:String\(\s*(com\.linroid\.ketch\.cloud\.compat\.util\.AndroidBase64\.decode\([^()]*\))\s*\)",
            r"\1.decodeToString()",
        ),
    ],
    # The Android WebView helpers; the iOS login screen lives in Swift.
    "data/network/XunleiWebCredential.kt": [
        ("__remove_fun__", "applyDesktopClientHints"),
        ("runCatching { android.net.Uri.parse(value) }", "runCatching { com.linroid.ketch.cloud.compat.util.URI(value) }"),
    ],
}
