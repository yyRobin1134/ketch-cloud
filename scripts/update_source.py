"""Add a build to source.json (newest first, keeps the last 10)."""
import argparse, json, pathlib

p = argparse.ArgumentParser()
for a in ("version", "build", "url", "size", "date", "notes"):
    p.add_argument("--" + a, required=True)
a = p.parse_args()

path = pathlib.Path(__file__).resolve().parent.parent / "source.json"
src = json.loads(path.read_text(encoding="utf-8"))
app = src["apps"][0]
entry = {
    "version": a.version,
    "buildVersion": a.build,
    "date": a.date,
    "localizedDescription": a.notes,
    "downloadURL": a.url,
    "size": int(a.size),
    "minOSVersion": "15.0",
}
app["versions"] = [entry] + [v for v in app["versions"] if v["downloadURL"] != a.url][:9]
# legacy fields read by older AltStore/SideStore builds
app.update(version=entry["version"], versionDate=entry["date"], downloadURL=entry["downloadURL"],
           size=entry["size"], versionDescription=entry["localizedDescription"])
path.write_text(json.dumps(src, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("source.json ->", a.version, a.build)
