"""Package the Windows P4C meter with its offline test instructions."""

from hashlib import sha256
from pathlib import Path
from struct import unpack_from
from sys import argv
from zipfile import ZIP_DEFLATED, ZipFile


def package(dist: Path) -> None:
    source = Path(__file__).resolve().parent
    binary = (dist / "svc-wasapi-check.exe").read_bytes()
    pe = unpack_from("<I", binary, 0x3C)[0]
    if binary[:2] != b"MZ" or binary[pe:pe + 6] != b"PE\0\0\x64\x86":
        raise ValueError("Expected a Windows x64 executable")

    files = {"svc-wasapi-check.exe": binary}
    for name, filename in (("README.txt", "README-WINDOWS.md"), ("TEST-RESULTS.txt", "TEST-RESULTS-WINDOWS.txt")):
        files[name] = (source / filename).read_text(encoding="utf-8").replace("\n", "\r\n").encode("utf-8-sig")

    archive = dist / "svc-broadcast-p4c-win11-x64-test1.zip"
    with ZipFile(archive, "w", ZIP_DEFLATED, compresslevel=9) as output:
        for name, content in files.items():
            output.writestr(name, content)
    with ZipFile(archive) as output:
        if output.testzip() or {name: output.read(name) for name in output.namelist()} != files:
            raise ValueError("Windows test ZIP failed verification")

    digest = sha256(archive.read_bytes()).hexdigest()
    archive.with_suffix(".zip.sha256").write_text(f"{digest}  {archive.name}\n", encoding="ascii")
    print(f"{archive.resolve()} ({archive.stat().st_size} bytes)\nSHA-256 {digest}")


if __name__ == "__main__":
    package(Path(argv[1]))
