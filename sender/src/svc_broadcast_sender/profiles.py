"""Explicit game-paired, per-server trust. No device access or implicit networking."""

import base64
import ctypes
import http.client
import json
import os
from pathlib import Path
import re
import ssl
import stat
import sys
import tempfile
import time
from urllib.parse import urlsplit
from uuid import UUID

from .config import validate_url
from .errors import InputError, SenderError

MAX_PAIRING_LENGTH = 16384


def _object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError
        result[key] = value
    return result


def _json(raw):
    return json.loads(raw, object_pairs_hook=_object)


def _uuid(value):
    return isinstance(value, str) and str(UUID(value)) == value


def profile_context(ca):
    try:
        if not isinstance(ca, str) or len(ca) > 8192 or ca.count("-----BEGIN CERTIFICATE-----") != 1:
            raise ValueError
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_verify_locations(cadata=ca)
        # Deliberately do not load system CAs or SSLKEYLOGFILE for this trust mode.
        return context
    except (ValueError, ssl.SSLError):
        raise InputError("profile_trust", "Invalid server-specific certificate trust.") from None


def _endpoint(url):
    validate_url(url)
    parts = urlsplit(url)
    if parts.scheme != "wss" or parts.path != "/v1/device/audio":
        raise ValueError
    return parts


_IDENTITY_FIELDS = {"version", "server_id", "url", "ca_pem"}


def _decode_pairing(value, prefix):
    if not isinstance(value, str) or len(value) > MAX_PAIRING_LENGTH or not value.startswith(prefix):
        raise ValueError
    encoded = value[len(prefix):]
    if not re.fullmatch(r"[A-Za-z0-9_-]+", encoded):
        raise ValueError
    return _json(base64.b64decode(encoded + "=" * (-len(encoded) % 4), altchars=b"-_", validate=True))


def _validate_identity(data):
    if type(data["version"]) is not int or data["version"] != 1 or not _uuid(data["server_id"]):
        raise ValueError
    _endpoint(data["url"])
    profile_context(data["ca_pem"])


def parse_pairing_identity(value):
    try:
        data = _decode_pairing(value, "svcid1:")
        if not isinstance(data, dict) or set(data) != _IDENTITY_FIELDS:
            raise ValueError
        _validate_identity(data)
        return data
    except (ValueError, TypeError, KeyError, SenderError):
        raise InputError("pairing_identity", "The Minecraft server did not provide a valid broadcast identity.") from None


def _same_identity(expected, profile):
    return all(profile[key] == expected[key] for key in _IDENTITY_FIELDS)


def find_paired_profile(name, player, identity, directory=None):
    try:
        valid_player = _uuid(player)
    except ValueError:
        valid_player = False
    if not valid_player:
        raise InputError("pairing_player", "The Minecraft player identity was invalid.")
    path = profile_path(name, directory)
    if path.exists() or path.is_symlink():
        profile = load_profile(name, directory)
        if profile["player_id"] != player or not _same_identity(identity, profile):
            raise InputError("profile_identity_changed", "The paired Minecraft broadcast identity changed.")
        return name
    conflict = False
    for candidate in list_profiles(directory):
        try:
            profile = load_profile(candidate, directory)
        except SenderError:
            continue
        if profile["player_id"] != player:
            continue
        if _same_identity(identity, profile):
            return candidate
        if profile["server_id"] == identity["server_id"] or profile["url"] == identity["url"]:
            conflict = True
    if conflict:
        raise InputError("profile_identity_changed", "The saved broadcast identity changed.")
    return None


def parse_pairing(value, *, now=None):
    try:
        data = _decode_pairing(value, "svcb1:")
        if not isinstance(data, dict) or set(data) != _IDENTITY_FIELDS | {"code", "expires_at"}:
            raise ValueError
        _validate_identity(data)
        if not isinstance(data["code"], str) or not re.fullmatch("[0-9a-f]{64}", data["code"]):
            raise ValueError
        current = time.time() * 1000 if now is None else now
        if type(data["expires_at"]) is not int or not current < data["expires_at"] <= current + 600_000:
            raise ValueError
        return data
    except (ValueError, TypeError, KeyError, OverflowError, SenderError):
        raise InputError("pairing_info", "Invalid or expired pairing information; copy a fresh private pair message in game.") from None


def validate_profile(data):
    try:
        if set(data) != {"version", "server_id", "url", "ca_pem", "token", "device_id", "player_id", "expires_at"}:
            raise ValueError
        if type(data["version"]) is not int or data["version"] != 1:
            raise ValueError
        if not all(_uuid(data[k]) for k in ("server_id", "device_id", "player_id")):
            raise ValueError
        _endpoint(data["url"])
        profile_context(data["ca_pem"])
        if not isinstance(data["token"], str) or not re.fullmatch("[0-9a-f]{64}", data["token"]):
            raise ValueError
        if type(data["expires_at"]) is not int or not 0 <= data["expires_at"] < 2**63:
            raise ValueError
        return data
    except (ValueError, TypeError, KeyError, SenderError):
        raise InputError("profile_invalid", "Invalid saved profile; restore it or explicitly remove and pair again.") from None


def default_directory():
    if sys.platform == "darwin":
        return Path.home() / "Library/Application Support/svc-broadcast/profiles"
    if os.name == "nt":
        return Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData/Local")) / "svc-broadcast/profiles"
    return Path.home() / ".config/svc-broadcast/profiles"


def _windows_private(path, directory):
    # Protected current-user DACL, using Windows' own APIs; no global settings.
    from ctypes import wintypes as w
    adv = ctypes.WinDLL("advapi32", use_last_error=True)
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    token = w.HANDLE()
    adv.OpenProcessToken.argtypes = [w.HANDLE, w.DWORD, ctypes.POINTER(w.HANDLE)]
    adv.GetTokenInformation.argtypes = [w.HANDLE, ctypes.c_int, ctypes.c_void_p, w.DWORD, ctypes.POINTER(w.DWORD)]
    adv.ConvertSidToStringSidW.argtypes = [ctypes.c_void_p, ctypes.POINTER(w.LPWSTR)]
    adv.ConvertStringSecurityDescriptorToSecurityDescriptorW.argtypes = [w.LPCWSTR, w.DWORD, ctypes.POINTER(ctypes.c_void_p), ctypes.POINTER(w.DWORD)]
    adv.SetFileSecurityW.argtypes = [w.LPCWSTR, w.DWORD, ctypes.c_void_p]
    kernel.GetCurrentProcess.restype = w.HANDLE
    kernel.LocalFree.argtypes = [ctypes.c_void_p]
    kernel.CloseHandle.argtypes = [w.HANDLE]
    descriptor = ctypes.c_void_p()
    sid_string = w.LPWSTR()
    try:
        if not adv.OpenProcessToken(kernel.GetCurrentProcess(), 8, ctypes.byref(token)):
            raise OSError
        needed = w.DWORD()
        adv.GetTokenInformation(token, 1, None, 0, ctypes.byref(needed))
        buf = ctypes.create_string_buffer(needed.value)
        if not adv.GetTokenInformation(token, 1, buf, needed, ctypes.byref(needed)):
            raise OSError
        sid = ctypes.cast(buf, ctypes.POINTER(ctypes.c_void_p))[0]
        if not adv.ConvertSidToStringSidW(sid, ctypes.byref(sid_string)):
            raise OSError
        flags = "OICI" if directory else ""
        sddl = f"D:P(A;{flags};FA;;;{sid_string.value})"
        if not adv.ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl, 1, ctypes.byref(descriptor), None):
            raise OSError
        if not adv.SetFileSecurityW(str(path), 0x80000004, descriptor):
            raise OSError
    finally:
        if descriptor: kernel.LocalFree(descriptor)
        if sid_string: kernel.LocalFree(ctypes.cast(sid_string, ctypes.c_void_p))
        if token: kernel.CloseHandle(token)


def _private(path, *, directory=False):
    if path.is_symlink():
        raise OSError
    if os.name == "nt":
        _windows_private(path, directory)
    else:
        if path.stat().st_uid != os.getuid():
            raise OSError
        path.chmod(0o700 if directory else 0o600)


def profile_path(name, directory=None):
    if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,32}", name):
        raise InputError("profile_name", "Profile names use 1-32 ASCII letters, digits, underscores or hyphens.")
    return Path(directory) / (name + ".json") if directory is not None else default_directory() / (name + ".json")


def save_profile(name, data, directory=None):
    validate_profile(data)
    path = profile_path(name, directory)
    temp = None
    try:
        if path.exists() or path.is_symlink():
            raise InputError("profile_exists", "Profile already exists; remove it explicitly before pairing again.")
        path.parent.mkdir(parents=True, exist_ok=True)
        _private(path.parent, directory=True)
        fd, temporary = tempfile.mkstemp(prefix=".profile-", dir=path.parent)
        temp = Path(temporary)
        try:
            _private(temp)
            with os.fdopen(fd, "w", encoding="utf-8") as out:
                fd = None
                out.write(json.dumps(data, ensure_ascii=False))
                out.flush()
                os.fsync(out.fileno())
            # Link is an atomic no-clobber publication; do not replace a competing profile.
            os.link(temp, path)
        finally:
            if fd is not None: os.close(fd)
    except InputError:
        raise
    except OSError:
        raise InputError("profile_permissions", "Cannot safely save the private profile.") from None
    finally:
        if temp is not None: temp.unlink(missing_ok=True)


def load_profile(name, directory=None):
    path = profile_path(name, directory)
    try:
        _private(path.parent, directory=True)
        _private(path)
        flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
        fd = os.open(path, flags)
        with os.fdopen(fd, "rb") as stream:
            if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
                raise OSError
            raw = stream.read(32769)
        if len(raw) > 32768:
            raise ValueError
        return validate_profile(_json(raw))
    except SenderError:
        raise
    except (OSError, ValueError, TypeError):
        raise InputError("profile_file", "Cannot safely read this private profile.") from None


def list_profiles(directory=None):
    root = Path(directory) if directory is not None else default_directory()
    if not root.exists(): return []
    try:
        _private(root, directory=True)
        names = sorted(path.stem for path in root.glob("*.json"))
        for name in names: profile_path(name, root)
        return names
    except OSError:
        raise InputError("profile_permissions", "Cannot safely read the profile directory.") from None


def remove_profile(name, directory=None):
    path = profile_path(name, directory)
    try:
        _private(path.parent, directory=True)
        _private(path)
        if not path.is_file(): raise OSError
        path.unlink()
    except OSError: raise InputError("profile_file", "Cannot remove the private profile.") from None


def pair(name, blob, label, directory=None, *, expected_identity=None, expected_player=None):
    path = profile_path(name, directory)
    if path.exists() or path.is_symlink():
        raise InputError("profile_exists", "Remove the existing profile explicitly before pairing again.")
    if not isinstance(label, str) or not label.strip() or len(label) > 64 or any(ord(c) < 32 or ord(c) == 127 for c in label):
        raise InputError("device_label", "Choose a short device label without control characters.")
    # Prepare and check private storage before consuming a one-time code.
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        _private(path.parent, directory=True)
    except OSError:
        raise InputError("profile_permissions", "Cannot prepare private profile storage.") from None
    info = parse_pairing(blob)
    if expected_identity is not None and not _same_identity(expected_identity, info):
        raise InputError("profile_identity_changed", "The server identity changed during pairing.")
    parts = _endpoint(info["url"])
    connection = http.client.HTTPSConnection(parts.hostname, parts.port or 443,
                                             context=profile_context(info["ca_pem"]), timeout=5)
    try:
        payload = json.dumps({"code": info["code"], "label": label, "server_id": info["server_id"]})
        connection.request("POST", "/v1/pair", body=payload.encode(), headers={"Content-Type": "application/json"})
        response = connection.getresponse()
        if response.status != 200:
            raise SenderError("pairing_rejected", "Pairing rejected; check qualification, expiry and device limit.", 3)
        raw = response.read(4097)
        if len(raw) > 4096: raise ValueError
        issued = _json(raw)
        if set(issued) != {"version", "server_id", "device_id", "player_id", "token", "expires_at"} or issued["server_id"] != info["server_id"]:
            raise ValueError
        if expected_player is not None and issued["player_id"] != expected_player:
            raise InputError("pairing_player", "The issued device belongs to a different Minecraft player.")
        data = validate_profile({**issued, "url": info["url"], "ca_pem": info["ca_pem"]})
        save_profile(name, data, directory)
    except SenderError:
        raise
    except ssl.SSLCertVerificationError:
        raise SenderError("tls_verification", "Server identity verification failed; obtain pairing information from the trusted game server.", 3) from None
    except (OSError, http.client.HTTPException, ValueError, TypeError):
        raise SenderError("pairing_connection", "Pairing failed; no alternate server identity was accepted.", 3) from None
    finally:
        connection.close()
