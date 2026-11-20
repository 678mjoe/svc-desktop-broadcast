"""Pack the Apple Silicon Minecraft sender using a relocatable CPython."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
from tempfile import TemporaryDirectory
import tomllib
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parents[2]
NAME = "svc-broadcast-p4d-macos-arm64"
RUNTIME = "3.12.13"
BUILD = "20260718"
PACKAGES = ("cffi", "numpy", "pycparser", "sounddevice", "soxr", "websockets")
HELPER = Path("SVC Audio Check.app/Contents/MacOS/svc-audio-check")


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def requirements(packages):
    lines = []
    for name in PACKAGES:
        package = packages[name]
        hashes = [wheel["hash"] for wheel in package["wheels"] if (
            "macosx_" in wheel["url"]
            and ("arm64" in wheel["url"] or "universal2" in wheel["url"])
        ) or wheel["url"].endswith("py3-none-any.whl")]
        if not hashes:
            raise ValueError("No Apple Silicon wheel in uv.lock for " + name)
        lines.append(f"{name}=={package['version']} " + " ".join(f"--hash={value}" for value in hashes))
    return "\n".join(lines) + "\n"


def build(work, output, python, soxr_source, offline, cache_dir):
    if platform.system() != "Darwin" or platform.machine() != "arm64":
        raise ValueError("Build on Apple Silicon macOS.")
    runtime = python.resolve().parents[1]
    version = subprocess.check_output([python, "-I", "-B", "--version"], text=True).strip()
    if version != f"Python {RUNTIME}" or (runtime / "BUILD").read_text().strip() != BUILD:
        raise ValueError(f"Use python-build-standalone {RUNTIME} build {BUILD}.")
    bundle = work / NAME
    app = bundle / "app"
    app.mkdir(parents=True)
    launcher = bundle / "runtime/bin/python3.12"
    launcher.parent.mkdir(parents=True)
    shutil.copy2(python, launcher)
    # The standalone executable provides libpython itself. Tcl/Tk, pip and developer tools
    # are outside the sender's dependency closure.
    shutil.copytree(runtime / "lib/python3.12", bundle / "runtime/lib/python3.12",
                    ignore=shutil.ignore_patterns("__pycache__", "site-packages", "test", "config-*",
                                                  "ensurepip", "tkinter", "idlelib", "_tkinter.*"))
    shutil.copytree(ROOT / "sender/src/svc_broadcast_sender", app / "svc_broadcast_sender",
                    ignore=shutil.ignore_patterns("__pycache__"))
    shutil.copy2(Path(__file__).with_name("launch.py"), app)
    signed_app = ROOT / "dist" / HELPER.parents[2]
    subprocess.run(["codesign", "--verify", "--strict", signed_app], check=True)
    shutil.copytree(signed_app, app / HELPER.parents[2])

    packages = {p["name"]: p for p in tomllib.loads((ROOT / "sender/uv.lock").read_text())["package"]}
    req = bundle / "requirements-macos.lock"
    req.write_text(requirements(packages), encoding="utf-8")
    uv = ["uv"]
    if cache_dir is not None:
        uv.extend(["--cache-dir", str(cache_dir)])
    if offline:
        uv.append("--offline")
    index = packages["numpy"]["source"]["registry"]
    subprocess.run([*uv, "pip", "install", "--python", str(python), "--target", str(app / "vendor"),
                    "--default-index", index, "--no-deps", "--only-binary", ":all:", "--require-hashes",
                    "--requirements", str(req)], check=True)
    source = packages["soxr"]["sdist"]
    if digest(soxr_source) != source["hash"].removeprefix("sha256:"):
        raise ValueError("SoXR source differs from uv.lock.")
    (bundle / "third-party-source").mkdir()
    shutil.copy2(soxr_source, bundle / "third-party-source/soxr-1.0.0.tar.gz")
    (bundle / "BUILD-MANIFEST.json").write_text(json.dumps({
        "target": "macOS 14.2+ Apple Silicon",
        "python": RUNTIME,
        "runtime_build": BUILD,
        "runtime_source": f"https://github.com/astral-sh/python-build-standalone/releases/tag/{BUILD}",
        "dependency_versions": {name: packages[name]["version"] for name in PACKAGES},
        "soxr_corresponding_source": source,
    }, indent=2) + "\n", encoding="utf-8")

    if any(path.is_symlink() for path in bundle.rglob("*")):
        raise ValueError("Do not include symlinks in the sender archive.")
    subprocess.run([launcher, "-I", "-B", "-X", "utf8", app / "launch.py",
                    "--profiles-dir", str(work / "test-profiles"), "mc-bridge", "profiles",
                    "--game-pid", str(os.getpid())], cwd=work, check=True,
                   capture_output=True)  # No capture or network access.
    output.mkdir(parents=True, exist_ok=True)
    archive = output / (NAME + ".zip")
    with ZipFile(archive, "w", ZIP_DEFLATED, compresslevel=6) as target:
        for path in sorted(bundle.rglob("*")):
            if path.is_file():
                target.write(path, path.relative_to(work).as_posix())
    archive.with_suffix(".zip.sha256").write_text(f"{digest(archive)}  {archive.name}\n", encoding="ascii")
    print(archive)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--python", type=Path, required=True, help="standalone Python 3.12.13 executable")
    parser.add_argument("--soxr-source", type=Path, required=True, help="SoXR source archive from uv.lock")
    parser.add_argument("--output", type=Path, default=ROOT / "dist")
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--cache-dir", type=Path)
    args = parser.parse_args()
    with TemporaryDirectory(prefix="svc-macos-bundle-") as temporary:
        build(Path(temporary), args.output, args.python.absolute(), args.soxr_source, args.offline, args.cache_dir)


if __name__ == "__main__":
    main()
