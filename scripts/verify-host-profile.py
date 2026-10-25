"""Read-only verification of this plugin's Apple Music colour profile against a supplied package.

Evidence checked per build (no APK code is executed, nothing is written outside a temp dir):
  * the package's own manifest tuple selects the profile (exact package / versionName / versionCode),
  * every colour resource named by the profile exists in the APK (via `aapt2 dump resources`),
  * the player-root view id exists,
  * every player colour resolves (through @color aliases) to plain white, the only value the plugin recolours,
  * every accent colour resolves to a literal colour.

Usage:
    python3 scripts/verify-host-profile.py apple-music-1613.apks --glass   # the profile is chosen from the APK's own version
`--glass` is accepted for parity with the sibling plugin scripts and turns on the strict value checks.
"""
import argparse
import io
import json
import struct
import sys
import zipfile
import importlib.util
from pathlib import Path

_manifest_spec = importlib.util.spec_from_file_location('android_manifest',Path(__file__).with_name('android-package-manifest.py'))
_manifest_module = importlib.util.module_from_spec(_manifest_spec)
_manifest_spec.loader.exec_module(_manifest_module)


def _uleb(data, offset):
    value = 0
    for shift in range(0, 35, 7):
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if not byte & 128:
            return value, offset
    raise ValueError("Invalid ULEB128")


def dex_classes(data):
    """Return {class descriptor: {"super": ..., "methods": set, "fields": dict}}."""
    if not data.startswith(b"dex\n"):
        raise ValueError("Not a DEX file")

    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]

    def u16(offset):
        return struct.unpack_from("<H", data, offset)[0]

    strings = []
    for i in range(u32(56)):
        _, offset = _uleb(data, u32(u32(60) + 4 * i))
        strings.append(data[offset:data.index(0, offset)].decode("utf8", errors="replace"))
    types = [strings[u32(u32(68) + 4 * i)] for i in range(u32(64))]
    protos = []
    for i in range(u32(72)):
        base = u32(76) + i * 12
        parameters = u32(base + 8)
        params = "" if not parameters else "".join(
            types[u16(parameters + 4 + j * 2)] for j in range(u32(parameters))
        )
        protos.append("(" + params + ")" + types[u32(base + 4)])
    field_defs = []
    for i in range(u32(80)):
        _, type_idx, name_idx = struct.unpack_from("<HHI", data, u32(84) + i * 8)
        field_defs.append((strings[name_idx], types[type_idx]))
    method_defs = []
    for i in range(u32(88)):
        _, proto_idx, name_idx = struct.unpack_from("<HHI", data, u32(92) + i * 8)
        method_defs.append(strings[name_idx] + protos[proto_idx])
    classes = {}
    for i in range(u32(96)):
        base = u32(100) + i * 32
        class_name = types[u32(base)]
        superclass = u32(base + 8)
        offset = u32(base + 24)
        methods = set()
        fields = {}
        method_access = {}
        field_access = {}
        if offset:
            counts = []
            for _ in range(4):
                count, offset = _uleb(data, offset)
                counts.append(count)
            for section in counts[:2]:
                index = 0
                for _ in range(section):
                    delta, offset = _uleb(data, offset)
                    index += delta
                    flags, offset = _uleb(data, offset)
                    fields[field_defs[index][0]] = field_defs[index][1]
                    field_access[field_defs[index][0]] = flags
            for section in counts[2:]:
                index = 0
                for _ in range(section):
                    delta, offset = _uleb(data, offset)
                    index += delta
                    flags, offset = _uleb(data, offset)
                    _, offset = _uleb(data, offset)
                    methods.add(method_defs[index])
                    method_access[method_defs[index]] = flags
        classes[class_name] = {
            "super": types[superclass] if superclass != 0xFFFFFFFF else None,
            "methods": methods,
            "fields": fields,
            "method_access": method_access,
            "field_access": field_access,
        }
    return classes


PROFILE_DIRECTORY = Path(__file__).resolve().parents[1] / "media-plugin/src/main/assets"



import re
import shutil
import subprocess
import tempfile

PROFILE_DIRECTORY = Path(__file__).resolve().parents[1] / "monet-plugin/src/main/assets"


def find_aapt2():
    found = shutil.which("aapt2")
    if found:
        return found
    candidates = sorted(Path("/opt/android-sdk/build-tools").glob("*/aapt2"), reverse=True)
    for sdk in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        import os
        if os.environ.get(sdk):
            candidates += sorted(Path(os.environ[sdk]).glob("build-tools/*/aapt2"), reverse=True)
    if not candidates:
        raise SystemExit("aapt2 not found")
    return str(candidates[0])


def open_base_apk(path):
    """Return (identity tuple, bytes of the base APK) for an apk/apkm/xapk/apks."""
    raw = Path(path).read_bytes()
    package = zipfile.ZipFile(io.BytesIO(raw))
    names = package.namelist()
    if "AndroidManifest.xml" in names:
        data = raw
    else:
        base = next((n for n in names if n.endswith("base.apk")), None) or \
            next(n for n in names if n.endswith(".apk") and "config" not in n)
        data = package.read(base)
    manifest = zipfile.ZipFile(io.BytesIO(data)).read("AndroidManifest.xml")
    return _manifest_module.manifest_identity(manifest), data


def dump_resources(apk_bytes):
    with tempfile.TemporaryDirectory() as directory:
        target = Path(directory) / "base.apk"
        target.write_bytes(apk_bytes)
        out = subprocess.run([find_aapt2(), "dump", "resources", str(target)], check=True,
                             capture_output=True, text=True).stdout
    resources = {}
    current = None
    for line in out.splitlines():
        match = re.match(r"\s+resource 0x[0-9a-f]+ (\w+)/(\S+)", line)
        if match:
            current = (match.group(1), match.group(2))
            resources[current] = {}
            continue
        value = re.match(r"\s+\((\w*)\) (.*)", line)
        if value and current:
            resources[current][value.group(1)] = value.group(2).strip()
    return resources


def resolve_color(resources, name, config="", depth=0):
    values = resources.get(("color", name), {})
    value = values.get(config, values.get(""))
    if value is None or depth > 8:
        return None
    if value.startswith("@color/"):
        return resolve_color(resources, value[len("@color/"):], config, depth + 1)
    return value if re.fullmatch(r"#[0-9a-fA-F]{8}", value) else None


def verify(apk_path, strict, profile_file=None):
    (package_name, version_name, version_code), apk = open_base_apk(apk_path)
    index = json.loads((PROFILE_DIRECTORY / "profiles.json").read_text(encoding="utf-8"))
    entry = next((e for e in index["profiles"] if index["packageName"] == package_name and
                  e["versionName"] == version_name and e["versionCode"] == int(version_code)), None)
    if entry is None:
        raise SystemExit(f"FAIL: no shipped profile for {package_name} {version_name} / {version_code}")
    profile = json.loads((profile_file or PROFILE_DIRECTORY / entry["asset"]).read_text(encoding="utf-8"))
    assert (profile["packageName"], profile["versionName"], profile["versionCode"]) == (package_name, version_name, int(version_code))
    monet = profile["monet"]
    resources = dump_resources(apk)
    problems = []
    for item in monet["accent"]:
        if ("color", item["name"]) not in resources:
            problems.append(f"missing accent colour {item['name']}")
        elif strict and resolve_color(resources, item["name"]) is None:
            problems.append(f"accent {item['name']} has no literal value")
    for name in [item["name"] for item in monet["player"]]:
        if ("color", name) not in resources:
            problems.append(f"missing player colour {name}")
        elif strict:
            value = resolve_color(resources, name)
            if value is None or value[3:].lower() != "ffffff":
                problems.append(f"player colour {name} is {value}, expected white with alpha")
    if ("id", monet["playerRootId"]) not in resources:
        problems.append(f"missing view id {monet['playerRootId']}")
    if problems:
        print(f"FAIL: {entry['asset']} against {version_name} / {version_code}")
        for problem in problems:
            print("  -", problem)
        return False
    print(f"PASS: {entry['asset']} matches {version_name} / {version_code}: "
          f"{len(monet['accent'])} accent + {len(monet['player'])} player colours, id/{monet['playerRootId']}")
    return True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("package", type=Path)
    parser.add_argument("--profile", type=Path, help="explicit profile asset (must still match the APK)")
    parser.add_argument("--glass", action="store_true", help="strict value checks")
    args = parser.parse_args()
    sys.exit(0 if verify(args.package, args.glass, args.profile) else 1)


if __name__ == "__main__":
    main()
