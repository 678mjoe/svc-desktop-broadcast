"""Command-line entry point; importing the package never starts an upload."""

import argparse
import asyncio
import json
import getpass
import math
import os
import sys
import time
import warnings
from contextlib import ExitStack
from collections.abc import Sequence

from .errors import InputError, SenderError
from .sources import ToneSource, WavSource
from .transport import upload


class _SafeParser(argparse.ArgumentParser):
    def error(self, message):
        # argparse's default diagnostic repeats arbitrary argv, including an
        # accidentally credential-bearing URL. Keep diagnostics local and fixed.
        raise InputError("arguments", "Invalid arguments; use --help for required options and supported sources.")


def parser() -> argparse.ArgumentParser:
    result = _SafeParser(
        prog="svc-broadcast-send",
        description="Explicit local input checks and audio upload from a selected device, test tone or WAV.",
        epilog="Exit codes: 0 completed; 1 internal failure; 2 input/config; 3 connection/auth/TLS; 4 server/protocol; 5 timeout; 130 interrupted.",
    )
    result.add_argument("--url", default=None, help="/v1/audio endpoint; remote endpoints require verified wss")
    result.add_argument("--token-file", help="legacy maintenance token file; use --profile for paired devices")
    result.add_argument("--ca-file", help="additional trusted PEM CA file for wss")
    result.add_argument("--profiles-dir", help="private profile directory; defaults to the platform user directory")
    sources = result.add_subparsers(dest="source", required=True)
    pairing = sources.add_parser("pair", help="paste private game pairing information via hidden input; no audio capture")
    pairing.add_argument("--name", required=True, help="local profile name")
    pairing.add_argument("--label", required=True, help="device label shown to server administrators")
    profiles = sources.add_parser("profiles", help="list or explicitly remove local profiles")
    profiles.add_argument("action", choices=("list", "remove"))
    profiles.add_argument("name", nargs="?")
    sources.add_parser("devices", help="list audio devices without opening a capture stream")
    sources.add_parser("apps", help="list native audio app candidates without starting capture")
    bridge = sources.add_parser("mc-bridge", help="local stdio controls for the Minecraft client prototype")
    bridge.add_argument("action", choices=("apps", "profiles", "start", "inspect", "pair"))
    bridge.add_argument("--profile")
    bridge.add_argument("--app", action="append")
    bridge.add_argument("--game-pid", type=int, required=True)
    bridge.add_argument("--player")
    for name in ("check-apps", "broadcast-apps"):
        apps = sources.add_parser(name, help="check or broadcast audio from explicitly selected apps")
        selection = apps.add_mutually_exclusive_group(required=True)
        selection.add_argument("--pid", type=int, action="append",
                               help="app process ID from apps; repeat for up to four apps")
        selection.add_argument("--app", action="append", help=argparse.SUPPRESS)
        if name == "check-apps":
            apps.add_argument("--seconds", type=float, default=15.0)
        else:
            apps.add_argument("--profile", required=True, help="saved game-paired server profile")
    for name, help_text in (
        ("capture", "upload an explicitly selected input, such as OBS monitoring via a virtual device"),
        ("check-input", "check an explicitly selected input locally; no upload, playback or recording"),
    ):
        capture = sources.add_parser(name, help=help_text)
        capture.add_argument("--device", required=True, help="exact input device name from devices; no default microphone")
        capture.add_argument("--host-api", help="exact backend name to disambiguate duplicate device names")
        capture.add_argument("--sample-rate", type=int, choices=(44100, 48000), default=48000)
        capture.add_argument("--channels", type=int, choices=(1, 2), default=2, help="source channels; stereo is averaged to mono for V1")
        capture.add_argument("--gain-db", type=float, default=0.0, help="input gain from -60 to +12 dB (default: 0)")
        if name == "capture":
            capture.add_argument("--profile", help="saved game-paired server profile")
        if name == "check-input":
            capture.add_argument("--seconds", type=float, default=10.0, help="local check duration, greater than 0 and at most 60 seconds")
    tone = sources.add_parser("tone", help="finite low-volume 440 Hz test tone")
    tone.add_argument("--profile", help="saved game-paired server profile")
    tone.add_argument("--seconds", type=float, default=5.0, help="duration greater than 0 and at most 30 seconds (default: 5)")
    wav = sources.add_parser("wav", help="uncompressed 48 kHz mono 16-bit WAV file")
    wav.add_argument("--profile", help="saved game-paired server profile")
    wav.add_argument("file", help="local WAV file")
    return result


def _print_capture_diagnostics(source):
    diagnostics = getattr(source, "diagnostics", None)
    if diagnostics is not None:
        print("Capture diagnostics: " + json.dumps(diagnostics(), allow_nan=False), flush=True)


def _print_levels(source):
    stats = source.stats()
    print(
        f"Audio: peak={stats['peak_dbfs']:.1f} dBFS, RMS={stats['rms_dbfs']:.1f} dBFS; "
        f"clipped={stats['clipped_samples']}, dropped={stats['dropped_frames']}, "
        f"queued={stats['queued_frames']} frames",
        flush=True,
    )


def _check_input(source, seconds):
    started = time.monotonic()
    next_report = started + 1
    frames = 0
    try:
        while time.monotonic() - started < seconds:
            source.read_frame(frames)
            frames += 1
            if time.monotonic() >= next_report:
                _print_levels(source)
                _print_capture_diagnostics(source)
                next_report = time.monotonic() + 1
        _print_levels(source)
        print(f"Input check completed: read {frames} frames. Silence is valid input; "
              "this does not verify music or listener playback.", flush=True)
    finally:
        # Report the failing state before closing; release even on Ctrl+C.
        try:
            _print_capture_diagnostics(source)
        finally:
            source.close()


def _check_apps(source, seconds):
    started = time.monotonic()
    next_report = started + 1
    sequence = 0
    stop_at = None
    try:
        while stop_at is None or time.monotonic() < stop_at:
            source.read_frame(sequence)
            sequence += 1
            if stop_at is None:
                if source.ready:
                    stop_at = time.monotonic() + seconds
                else:
                    time.sleep(0.02)
                    continue
            if time.monotonic() >= next_report:
                _print_levels(source)
                next_report = time.monotonic() + 1
        source.verify_check_signal()
    finally:
        try:
            _print_capture_diagnostics(source)
        finally:
            source.close()


async def _capture_upload(source, args):
    async def levels():
        while True:
            await asyncio.sleep(1)
            _print_levels(source)

    reporter = asyncio.create_task(levels())
    try:
        return await upload(source, url=args.url, token_file=args.token_file, ca_file=args.ca_file, **({"profile": args.profile_data} if getattr(args,"profile_data",None) else {}))
    finally:
        reporter.cancel()
        await asyncio.gather(reporter, return_exceptions=True)
        # Close before asyncio.run waits for source-reading executor threads.
        try:
            _print_capture_diagnostics(source)
        finally:
            await asyncio.to_thread(source.close)


def _app_source(args):
    if sys.platform == "darwin":
        if args.app is not None:
            raise InputError("coreaudio_selection", "On Mac, use --pid with a process listed by apps.")
        from .coreaudio import CoreAudioSource
        return CoreAudioSource(args.pid)
    from .wasapi import source_from_selection
    return source_from_selection(args.pid, args.app)


def _list_apps():
    if sys.platform == "darwin":
        from .coreaudio import list_apps
        print(list_apps(), end="")
    else:
        from .wasapi import list_apps
        for app in list_apps():
            kind = "active audio session" if app.active_session else "window"
            print(f"pid={app.pid}  {app.label}  ({kind})")


def _read_posix_pairing(prompt: str, limit: int) -> str:
    import termios
    import tty

    # macOS canonical input stalls on pastes of 1024 bytes or more.
    # Keep echo off for the entire paste and restore the terminal on every exit.
    fd = sys.stdin.fileno()
    original = termios.tcgetattr(fd)
    try:
        tty.setcbreak(fd, termios.TCSAFLUSH)
        print(prompt, end="", file=sys.stderr, flush=True)
        line = bytearray()
        too_long = False
        while chunk := os.read(fd, 4096):
            for byte in chunk:
                if byte in (10, 13):
                    return "" if too_long else line.decode("ascii", errors="replace")
                if byte == 4:  # Ctrl+D in noncanonical mode
                    raise EOFError
                if not too_long:
                    if byte in (8, 127):
                        del line[-1:]
                    elif len(line) < limit:
                        line.append(byte)
                    else:
                        # Drain an oversized paste without echo until Enter.
                        too_long = True
        raise EOFError
    finally:
        termios.tcsetattr(fd, termios.TCSAFLUSH, original)
        print(file=sys.stderr, flush=True)


def _read_pairing_info() -> str:
    from .profiles import MAX_PAIRING_LENGTH

    prompt = "Paste private pairing information from the game (hidden): "
    try:
        if sys.platform == "win32":
            with warnings.catch_warnings():
                # Never let getpass fall back to visible input.
                warnings.simplefilter("error", getpass.GetPassWarning)
                return getpass.getpass(prompt)
        import termios

        try:
            return _read_posix_pairing(prompt, MAX_PAIRING_LENGTH)
        except termios.error:
            raise OSError from None
    except (getpass.GetPassWarning, OSError, ValueError):
        raise InputError("pairing_terminal", "Hidden input is unavailable; use an interactive terminal that supports echo control.") from None
    except EOFError:
        raise InputError("pairing_terminal", "Pairing input ended; run pair again to paste the private game message.") from None


def main(argv: Sequence[str] | None = None) -> int:
    try:
        args = parser().parse_args(argv)
        if args.source == "mc-bridge":
            from .mc_bridge import run
            return run(args)
        if args.source == "pair":
            if not sys.stdin.isatty():
                raise InputError("pairing_terminal", "Pairing requires an interactive terminal for hidden input; do not pipe pairing secrets.")
            from .profiles import pair
            pair(args.name, _read_pairing_info(), args.label, args.profiles_dir)
            print("Device paired. Use capture --profile with this name; no audio input was opened.")
            return 0
        if args.source == "profiles":
            from .profiles import list_profiles, remove_profile
            if args.action == "list":
                if args.name is not None: raise InputError("arguments", "Profile list takes no name.")
                print(json.dumps(list_profiles(args.profiles_dir)))
            else:
                remove_profile(args.name, args.profiles_dir)
                print("Local profile removed. Server-side device authorization must be revoked separately.")
            return 0
        if args.source == "devices":
            from dataclasses import asdict
            from .capture import list_devices

            print(json.dumps([asdict(device) for device in list_devices()], ensure_ascii=False, indent=2))
            return 0
        if args.source == "apps":
            _list_apps()
            return 0
        if args.source == "check-input":
            if not math.isfinite(args.seconds) or not 0 < args.seconds <= 60:
                raise InputError("check_duration", "Input check duration must be greater than 0 and at most 60 seconds.")
            from .capture import LiveSource, resolve_device

            source = LiveSource(resolve_device(args.device, args.host_api),
                                sample_rate=args.sample_rate, channels=args.channels, gain_db=args.gain_db)
            print("Checking explicitly selected input locally; Ctrl+C stops. "
                  "No upload, audio output or recording.", flush=True)
            _check_input(source, args.seconds)
            return 0
        if args.source == "check-apps":
            if any(value is not None for value in (args.url, args.token_file, args.ca_file, args.profiles_dir)):
                raise InputError("arguments", "Local app checks take only process IDs and duration.")
            if not math.isfinite(args.seconds) or not 1 <= args.seconds <= 60:
                raise InputError("check_duration", "App check duration must be between 1 and 60 seconds.")
            source = _app_source(args)
            print(f"Checking selected app PIDs {source.pids} locally. Ctrl+C stops.", flush=True)
            _check_apps(source, args.seconds)
            return 0
        args.profile_data = None
        if getattr(args,"profile",None):
            if args.token_file is not None or args.ca_file is not None or args.url is not None:
                raise InputError("profile_override", "Use a saved profile without manual endpoint or credential overrides.")
            from .profiles import load_profile
            args.profile_data=load_profile(args.profile,args.profiles_dir)
            args.url=args.profile_data["url"]
        elif not args.token_file:
            raise InputError("token_required", "Use --profile on the upload command, or a maintenance --token-file before it.")
        if args.url is None: args.url="ws://127.0.0.1:24456/v1/audio"
        with ExitStack() as stack:
            if args.source == "tone":
                source = ToneSource(args.seconds)
            elif args.source == "capture":
                from .capture import LiveSource, resolve_device

                device = resolve_device(args.device, args.host_api)
                source = LiveSource(device, sample_rate=args.sample_rate, channels=args.channels, gain_db=args.gain_db)
            elif args.source == "broadcast-apps":
                source = _app_source(args)
            else:
                source = stack.enter_context(WavSource(args.file))
            print("Connecting for explicit audio upload; Ctrl+C stops the session.", flush=True)
            if args.source == "broadcast-apps":
                print(f"Selected app PIDs: {source.pids}. Capture starts after server approval.", flush=True)
                result = asyncio.run(_capture_upload(source, args))
            elif args.source == "capture":
                print(f"Selected input: {json.dumps(device.name, ensure_ascii=False)}; {args.sample_rate} Hz, "
                      f"{args.channels} channel(s), gain {args.gain_db:g} dB. Capture starts after server approval.", flush=True)
                if not args.profile_data: print("Playing locally while in game? Use /svcbroadcast listen off on your own player "
                      "to avoid hearing the delayed broadcast too. Use /svcbroadcast listen on to restore listening.",
                      flush=True)
                result = asyncio.run(_capture_upload(source, args))
            else:
                result = asyncio.run(
                    upload(source, url=args.url, token_file=args.token_file, ca_file=args.ca_file,
                           **({"profile":args.profile_data} if args.profile_data else {}))
                )
        print(f"Completed: sent {result.sent_frames} frames; dropped {result.dropped_frames} stale source frames.")
        return 0
    except (KeyboardInterrupt, asyncio.CancelledError):
        print("interrupted: Upload stopped.", file=sys.stderr)
        return 130
    except SenderError as error:
        print(f"{error.code}: {error.message}", file=sys.stderr)
        return error.exit_code
    except Exception:
        # No traceback may expose a header, URL, token, or remote error payload.
        print("internal_error: Upload failed unexpectedly.", file=sys.stderr)
        return 1
