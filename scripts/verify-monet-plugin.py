"""Check the actual plugin ZIP: layout, manifest, profiles, DEX integrity and that no SDK/host code is bundled."""
import argparse
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import struct
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[1]
PKG = "Ldev/kifranei/ampp/monet/"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=Path)
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location("host_dex", ROOT / "scripts/verify-host-profile.py")
    dex = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(dex)
    definitions = {}
    source = json.loads((ROOT / "monet-plugin/plugin.json").read_text(encoding="utf-8"))
    with zipfile.ZipFile(args.archive) as archive:
        names = archive.namelist()
        assert len(names) == len(set(names)), "Duplicate ZIP paths"
        assert {"plugin.json", "code.jar"} <= set(names)
        assert all(name in {"plugin.json", "code.jar"} or name.startswith("assets/") for name in names)
        manifest = json.loads(archive.read("plugin.json"))
        assert manifest == source
        assert manifest["id"] == "dev.kifranei.ampp.monet"
        assert args.archive.name == f"ampp-monet-cover-color-{manifest['versionName']}.zip", args.archive.name
        assert manifest["formatVersion"] == manifest["apiVersion"] == 1 and manifest["minAndroidApi"] == 28
        assert archive.getinfo("assets/licenses/AMpp-GPL-3.0.txt").file_size > 0
        index = json.loads(archive.read("assets/profiles.json"))
        shipped = [(item["versionName"], item["versionCode"], item["asset"]) for item in index["profiles"]]
        assert [code for _, code, _ in shipped] == [1606, 1607, 1609, 1611], shipped
        assert {n for n in names if n.startswith("assets/applemusic-")} == {"assets/" + asset for _, _, asset in shipped}
        for name, code, asset in shipped:
            profile = json.loads(archive.read("assets/" + asset))
            assert (profile["packageName"], profile["versionName"], profile["versionCode"]) == (index["packageName"], name, code), asset
            monet = profile["monet"]
            assert monet["schemaVersion"] == 1 and monet["playerRootId"] and monet["accent"] and monet["player"], asset
        with zipfile.ZipFile(io.BytesIO(archive.read("code.jar"))) as code:
            assert "classes.dex" in code.namelist()
            for name in code.namelist():
                assert re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", name), name
                data = code.read(name)
                assert data[:8] in (b"dex\n038\0", b"dex\n039\0")
                assert struct.unpack_from("<I", data, 32)[0] == len(data)
                assert hashlib.sha1(data[32:]).digest() == data[12:32]
                assert zlib.adler32(data[12:]) == struct.unpack_from("<I", data, 8)[0]
                classes = dex.dex_classes(data)
                assert not definitions.keys() & classes.keys(), "Duplicate DEX definitions"
                definitions.update(classes)
        entry = "L" + manifest["entryClass"].replace(".", "/") + ";"
        assert definitions[entry]["super"] == "Ldev/amenhancer/plugin/api/AmppPlugin;"
        assert "<init>()V" in definitions[entry]["methods"]
        assert "onLoad(Ldev/amenhancer/plugin/api/PluginContext;)V" in definitions[entry]["methods"]
        forbidden = ("Ldev/amenhancer/", "Lcom/apple/android/music/", "Lio/github/libxposed/",
                     "Lcom/kyant/backdrop/", "Landroidx/compose/")
        assert not any(name.startswith(forbidden) for name in definitions), "Private module, host or SDK code bundled"
        for name in ("MonetCoverPalette", "MonetScheme", "MonetState", "SeedCache", "CoverColorPipeline", "CoverMetadataSource",
                     "ResourceColorHooks", "LiveRecolor", "ColorRoleBook", "MonetColors", "MonetProfile", "ProfileIndex",
                     "MonetSettings", "MonetSettingsStore", "MonetMode", "MonetText", "ArtworkSampler"):
            assert PKG + name + ";" in definitions, name
        fields = definitions[PKG + "MonetSettings;"]["fields"]
        assert fields.get("mode") == PKG + "MonetMode;", fields
        assert definitions[PKG + "MonetMode;"]["fields"].keys() >= {"OFF", "PLAYER", "APP"}
    print(f"PASS: SDK v1 monet ZIP; {len(definitions)} DEX definitions; three modes; no private module/SDK copies")


if __name__ == "__main__":
    main()
