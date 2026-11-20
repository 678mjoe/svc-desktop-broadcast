"""Endpoint and credential validation before any connection is attempted."""

import ipaddress
import re
import ssl
from pathlib import Path
from urllib.parse import urlsplit

from .errors import InputError


def validate_url(url: str) -> str:
    if not isinstance(url, str) or not url or any(ord(char) <= 32 or ord(char) == 127 for char in url):
        raise InputError("endpoint", "Endpoint must be a ws or wss URL without whitespace.")
    try:
        parts = urlsplit(url)
        hostname = parts.hostname
        port = parts.port
        if (
            parts.scheme not in ("ws", "wss")
            or not hostname
            or parts.path not in ("/v1/audio", "/v1/device/audio")
            or parts.username is not None
            or parts.password is not None
            or "?" in url
            or "#" in url
            or "\\" in url
            or "%" in hostname
            or parts.netloc.endswith(":")
            or (port is not None and not 1 <= port <= 65535)
        ):
            raise ValueError
    except ValueError:
        raise InputError("endpoint", "Use a ws or wss endpoint at /v1/audio without credentials, query, or fragment.") from None
    if parts.scheme == "ws":
        try:
            loopback = ipaddress.ip_address(hostname).is_loopback
        except ValueError:
            loopback = hostname.lower() == "localhost"
        if not loopback:
            raise InputError("plaintext_remote", "Plain ws is allowed only for literal loopback IPs or localhost; use verified wss remotely.")
    return url


def read_token(path: str | Path) -> str:
    try:
        path = Path(path)
        if not path.is_file():
            raise OSError
        with path.open("rb") as token_file:
            raw = token_file.read(67)
    except (OSError, ValueError):
        raise InputError("token_file", "Cannot read the token file.") from None
    if not re.fullmatch(rb"[0-9a-f]{64}(?:\r?\n)?", raw):
        raise InputError("token_format", "Token file must contain exactly 64 lowercase hexadecimal characters and an optional final newline.")
    return raw.rstrip(b"\r\n").decode("ascii")


def tls_context(url: str, ca_file: str | Path | None) -> ssl.SSLContext | None:
    if urlsplit(url).scheme != "wss":
        if ca_file is not None:
            raise InputError("ca_plaintext", "--ca-file requires a wss endpoint.")
        return None
    try:
        context = ssl.create_default_context()
        if ca_file is not None:
            if not Path(ca_file).is_file():
                raise OSError
            context.load_verify_locations(cafile=str(ca_file))
        return context
    except (OSError, ValueError, ssl.SSLError):
        raise InputError("ca_file", "Cannot load trusted TLS certificates.") from None
