"""Build the Windows x64 test ZIP on macOS/Linux; never execute a Windows binary."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess
import tomllib
from urllib.request import urlopen
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parents[2]
NAME = "svc-broadcast-0.3.0-win11-x64-test2"
PYTHON = "cpython-3.12.13-windows-x86_64-none"
RUNTIME_URL = "https://releases.astral.sh/github/python-build-standalone/releases/download/20260718/cpython-3.12.13%2B20260718-x86_64-pc-windows-msvc-install_only_stripped.tar.gz"
PACKAGES = {"websockets", "sounddevice", "numpy", "soxr", "cffi", "pycparser"}
WINDOWS_DLLS = {name + ".dll" for name in (
    "kernel32", "version", "ws2_32", "bcrypt", "advapi32", "user32", "winmm", "ole32", "setupapi",
    "rpcrt4", "netapi32", "userenv", "crypt32", "gdi32", "shell32", "comdlg32", "comctl32",
    "imm32", "cabinet", "msi", "propsys", "oleaut32", "iphlpapi", "ntdll")}


def requirements(lock):
    selected = []
    for package in lock["package"]:
        if package["name"] not in PACKAGES: continue
        matches = [w for w in package["wheels"] if any(tag in w["url"] for tag in
                   ("cp312-cp312-win_amd64.whl", "cp312-abi3-win_amd64.whl", "py3-none-win_amd64.whl"))]
        if not matches: matches = [w for w in package["wheels"] if w["url"].endswith("py3-none-any.whl")]
        if len(matches) != 1: raise ValueError("Missing or ambiguous Windows wheel: " + package["name"])
        wheel = matches[0]
        selected.append({"name": package["name"], "version": package["version"], **wheel})
    if {x["name"] for x in selected} != PACKAGES: raise ValueError("Incomplete dependency closure")
    return sorted(selected, key=lambda x: x["name"])


def pe_machine(path):
    with path.open("rb") as source:
        header = source.read(64)
        if len(header) < 64 or header[:2] != b"MZ": raise ValueError("Invalid PE file")
        source.seek(struct.unpack_from("<I", header, 0x3C)[0])
        signature = source.read(6)
        if signature[:4] != b"PE\0\0": raise ValueError("Invalid PE signature")
        return struct.unpack_from("<H", signature, 4)[0]


def pe_imports(path):
    data = path.read_bytes()
    offset = struct.unpack_from("<I", data, 0x3C)[0]
    count = struct.unpack_from("<H", data, offset + 6)[0]
    optional_size = struct.unpack_from("<H", data, offset + 20)[0]
    optional = offset + 24
    assert struct.unpack_from("<H", data, optional)[0] == 0x20B
    sections = [struct.unpack_from("<IIII", data, offset + 24 + optional_size + i * 40 + 8) for i in range(count)]
    def position(rva):
        for virtual_size, address, raw_size, pointer in sections:
            if address <= rva < address + max(virtual_size, raw_size): return pointer + rva - address
        raise ValueError("Invalid PE import address")
    rva = struct.unpack_from("<I", data, optional + 120)[0]
    if not rva: return []
    result = []; at = position(rva)
    while any(data[at:at + 20]):
        name = position(struct.unpack_from("<I", data, at + 12)[0])
        result.append(data[name:data.index(0, name)].decode("ascii").lower()); at += 20
    return result


def build(work, output, wasapi_exe=None, offline=False, minecraft_prototype=False):
    work.mkdir(parents=True, exist_ok=True); output.mkdir(parents=True, exist_ok=True)
    if minecraft_prototype and not wasapi_exe:
        raise ValueError("The Minecraft prototype needs the WASAPI helper.")
    name = ("svc-broadcast-p4d-win11-x64-mc-test2" if minecraft_prototype else
            "svc-broadcast-p4c-win11-x64-live-test3" if wasapi_exe else NAME)
    bundle = work / name
    if bundle.exists(): shutil.rmtree(bundle)
    app = bundle / "app"; app.mkdir(parents=True)
    if minecraft_prototype:
        shutil.copy2(ROOT / "client" / "README.md", bundle / "README-MINECRAFT.md")
    uv = ["uv", "--cache-dir", str(work / "cache")]
    if offline:
        uv.append("--offline")
    subprocess.run([*uv, "python", "install", PYTHON, "--install-dir", str(work / "python"), "--no-bin", "--no-registry"], check=True)
    runtime = work / "python" / PYTHON
    # uv adds a host-side convenience symlink; do not follow/package it.
    shutil.copytree(runtime, bundle / "runtime", ignore=shutil.ignore_patterns("python", "__pycache__"))
    for relative in ("include", "libs", "Lib/test", "Lib/site-packages", "Scripts"):
        path = bundle / "runtime" / relative
        if path.is_dir(): shutil.rmtree(path)
    lock = tomllib.loads((ROOT / "sender/uv.lock").read_text())
    wheels = requirements(lock)
    source = next(p for p in lock["package"] if p["name"] == "soxr")["sdist"]
    source_cache = work / "soxr-1.0.0.tar.gz"
    expected = source["hash"].removeprefix("sha256:")
    if not source_cache.exists() or hashlib.sha256(source_cache.read_bytes()).hexdigest() != expected:
        if offline:
            raise ValueError("Verified SoXR source archive is unavailable in the offline cache")
        with urlopen(source["url"], timeout=30) as response: data = response.read(source["size"] + 1)
        if len(data) != source["size"] or hashlib.sha256(data).hexdigest() != expected: raise ValueError("SoXR source checksum mismatch")
        source_cache.write_bytes(data)
    (bundle / "third-party-source").mkdir()
    shutil.copy2(source_cache, bundle / "third-party-source/soxr-1.0.0.tar.gz")
    req = bundle / "requirements-windows.lock"
    req.write_text("# Exact Windows wheels from sender/uv.lock\n" + "\n".join(
        f"{x['name']} @ {x['url']} --hash={x['hash']}" for x in wheels) + "\n", encoding="utf-8")
    subprocess.run([*uv, "pip", "install", "--target", str(app / "vendor"), "--python-version", "3.12",
                    "--python-platform", "x86_64-pc-windows-msvc", "--no-deps", "--only-binary", ":all:",
                    "--require-hashes", "--requirements", str(req)], check=True)
    # SoXR imports the original MSVC filename; NumPy's pinned wheel carries the
    # same runtime under a delvewheel-mangled name. Preserve its bytes/notices.
    msvc = list((app / "vendor/numpy.libs").glob("msvcp140-*.dll"))
    if len(msvc) != 1: raise ValueError("Pinned MSVC runtime missing/ambiguous")
    alias = bundle / "runtime/msvcp140.dll"
    shutil.copy2(msvc[0], alias)
    dll_alias = {"source": str(msvc[0].relative_to(bundle)), "target": "runtime/msvcp140.dll",
                 "sha256": hashlib.sha256(alias.read_bytes()).hexdigest(), "modified": False}
    package = app / "svc_broadcast_sender"; package.mkdir()
    for path in sorted((ROOT / "sender/src/svc_broadcast_sender").glob("*.py")): shutil.copy2(path, package)
    shutil.copy2(ROOT / "sender/windows/windows_test.py", app)
    for filename in ("start.cmd", "send.cmd"):
        content = (ROOT / "sender/windows" / filename).read_text()
        (bundle / filename).write_bytes(content.replace("\r\n", "\n").replace("\n", "\r\n").encode("ascii"))
    for filename in ("README-WINDOWS.md", "TEST-RESULTS.txt", "THIRD-PARTY-NOTICES.txt"):
        shutil.copy2(ROOT / "sender/windows" / filename, bundle)
    shutil.copy2(ROOT / "sender/windows/README-WINDOWS.md", bundle / "README-WINDOWS.txt")
    if wasapi_exe:
        shutil.copy2(wasapi_exe, app / "svc-wasapi-check.exe")
        for target, filename in (("README-P4C.txt", "LIVE-WINDOWS.md"), ("TEST-RESULTS-P4C.txt", "LIVE-RESULTS-WINDOWS.txt")):
            instructions = (ROOT / "sender/native" / filename).read_text(encoding="utf-8")
            (bundle / target).write_bytes(instructions.replace("\n", "\r\n").encode("utf-8-sig"))
    shutil.copy2(ROOT / "sender/pyproject.toml", bundle / "sender-pyproject.toml")
    shutil.copy2(ROOT / "sender/uv.lock", bundle / "sender-uv.lock")
    executables = [p for p in bundle.rglob("*") if p.suffix.lower() in (".exe", ".dll", ".pyd")]
    for path in executables:
        if pe_machine(path) != 0x8664: raise ValueError("Non-x64 binary: " + str(path.relative_to(bundle)))
    names = {p.name.lower() for p in executables}; os_libraries = set()
    for path in executables:
        for dependency in pe_imports(path):
            if dependency in names: continue
            if dependency in WINDOWS_DLLS or (wasapi_exe and dependency == "mmdevapi.dll") or dependency.startswith(("api-ms-", "ext-ms-")): os_libraries.add(dependency)
            else: raise ValueError("Unresolved native dependency: " + dependency + " in " + str(path.relative_to(bundle)))
    if list(bundle.rglob("*.dylib")) or list(bundle.rglob("*.so")) or any(p.is_symlink() for p in bundle.rglob("*")):
        raise ValueError("Foreign binary/symlink included")
    licenses = [str(p.relative_to(bundle)) for p in bundle.rglob("*") if p.is_file()
                and ("license" in p.name.lower() or "copying" in p.name.lower() or "notice" in p.name.lower())]
    assert len(licenses) >= 7, "Upstream license files missing"
    manifest = {"name": name, "sender_version": "0.3.0", "target": "Windows 11 x64",
                "python": "3.12.13", "runtime_source": RUNTIME_URL, "runtime_build": (runtime / "BUILD").read_text().strip(),
                "dependency_wheels": wheels, "x64_binary_count": len(executables), "licenses": licenses,
                "soxr_corresponding_source": source,
                "unmodified_dll_aliases": [dll_alias], "external_windows_libraries": sorted(os_libraries),
                "verification": "assembled and statically inspected on macOS; Windows execution/audio NOT verified",
                "includes_credentials": False}
    (bundle / "BUILD-MANIFEST.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    hashes = {str(p.relative_to(bundle)).replace("\\", "/"): hashlib.sha256(p.read_bytes()).hexdigest()
              for p in sorted(bundle.rglob("*")) if p.is_file()}
    (bundle / "FILES-SHA256.json").write_text(json.dumps(hashes, indent=2) + "\n", encoding="utf-8")
    archive = output / (name + ".zip")
    with ZipFile(archive, "w", ZIP_DEFLATED, compresslevel=6) as target:
        for path in sorted(bundle.rglob("*")):
            if path.is_file(): target.write(path, str(Path(name) / path.relative_to(bundle)))
    with ZipFile(archive) as target:
        if target.testzip() is not None: raise ValueError("ZIP integrity failure")
        if max(map(len, target.namelist())) > 210: raise ValueError("Excessive Windows extraction path")
    checksum = hashlib.sha256(archive.read_bytes()).hexdigest()
    archive.with_suffix(".zip.sha256").write_text(checksum + "  " + archive.name + "\n", encoding="ascii")
    print(json.dumps({"archive": str(archive), "bytes": archive.stat().st_size, "sha256": checksum,
                      "files": len(hashes) + 1, "x64_binaries": len(executables)}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work", type=Path, default=ROOT / "backups/windows-test-build")
    parser.add_argument("--output", type=Path, default=ROOT / "dist")
    parser.add_argument("--wasapi-exe", type=Path, help="package the P4C live capture helper")
    parser.add_argument("--offline", action="store_true", help="build using only existing runtime and wheel caches")
    parser.add_argument("--minecraft-prototype", action="store_true", help="name the P4D client companion bundle")
    args = parser.parse_args()
    build(args.work.resolve(), args.output.resolve(), args.wasapi_exe, args.offline, args.minecraft_prototype)
