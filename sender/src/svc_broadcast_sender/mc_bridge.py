"""Private stdio interface for the Minecraft sender companion."""

import asyncio
import json
import os
import re
import sys
import threading
import unicodedata

from .errors import InputError, SenderError
from .profiles import (MAX_PAIRING_LENGTH, find_paired_profile, list_profiles, load_profile, pair,
                       parse_pairing_identity)
from .transport import upload


def _emit(event, **fields):
    sys.stdout.buffer.write((json.dumps({"event": event, **fields}, ensure_ascii=False) + "\n").encode("utf-8"))
    sys.stdout.buffer.flush()


def _pairing_line():
    line = sys.stdin.buffer.readline(MAX_PAIRING_LENGTH + 2)
    if not line.endswith(b"\n") or len(line) > MAX_PAIRING_LENGTH + 1:
        raise InputError("pairing_info", "Expected a bounded private pairing message.")
    return line[:-1].decode("ascii")


def _pair_from_game(args):
    if args.profile is None or args.player is None or args.app or sys.stdin.isatty():
        raise InputError("bridge_arguments", "Pairing requires a private game pipe and player identity.")
    identity = parse_pairing_identity(_pairing_line())
    existing = find_paired_profile(args.profile, args.player, identity, args.profiles_dir)
    if existing is not None:
        _emit("paired", name=existing)
    elif args.action == "inspect":
        _emit("pair_required")
    else:
        pair(args.profile, _pairing_line(), "Minecraft sender", args.profiles_dir,
             expected_identity=identity, expected_player=args.player)
        _emit("paired", name=args.profile)


def _label(value, fallback):
    return "".join(
        ch for ch in value if unicodedata.category(ch)[0] != "C" and ch not in "\u2028\u2029§"
    ).strip()[:100] or fallback


def _apps(game_pid):
    if sys.platform == "darwin":
        from .coreaudio import list_apps

        result = {}
        for line in list_apps(identities=True).split("\n"):
            if not line:
                continue
            selector, bundle, label = line.split("\t", 2)
            if _pid(selector) != game_pid:
                result[selector] = _label(label + (" · " + bundle if bundle else ""), selector.split(":")[0])
        return [{"id": key, "label": label} for key, label in sorted(result.items(), key=lambda app: app[1].casefold())]
    if sys.platform == "win32":
        from .wasapi import list_apps

        return [
            {"id": app.selector,
             "label": _label(app.label + (" · 活动音频" if app.active_session else " · 窗口"), str(app.pid))}
            for app in list_apps(excluded_pid=game_pid) if app.pid != game_pid
        ]
    raise InputError("unsupported_platform", "App capture requires macOS or Windows.")


def _pid(selector):
    pattern = r"[0-9]{1,10}:[0-9]{1,20}:[0-9]{1,6}" if sys.platform == "darwin" else r"[0-9]{1,10}:[0-9]{1,20}"
    if not re.fullmatch(pattern, selector):
        raise InputError("app_selection", "List and select the apps again.")
    return int(selector.split(":", 1)[0])


def _source(selectors, game_pid):
    pids = [_pid(selector) for selector in selectors]
    if game_pid in pids or not 1 <= len(set(pids)) == len(pids) <= 4:
        raise InputError("app_selection", "Choose one to four distinct apps other than Minecraft.")
    if sys.platform == "darwin":
        from .coreaudio import CoreAudioSource
        return CoreAudioSource(pids, selectors=selectors)
    if sys.platform == "win32":
        from .wasapi import source_from_selection
        return source_from_selection(None, selectors, excluded_pid=game_pid)
    raise InputError("unsupported_platform", "App capture requires macOS or Windows.")


class _ObservedSource:
    frame_count = None

    def __init__(self, source):
        self.source = source
        self.opened = False
        self.announced = False

    def read_frame(self, index):
        if not self.opened:
            self.opened = True
            _emit("capture_starting")
        pcm = self.source.read_frame(index)
        if not self.announced and self.source.ready:
            self.announced = True
            _emit("capturing")
        return pcm


async def _start(profile, source, stdin_fd):
    loop = asyncio.get_running_loop()
    task = asyncio.current_task()

    def watch_parent():
        try:
            # Java closes the pipe on Stop or exit. A raw read avoids holding
            # Python's stdin buffer lock if the server ends the upload first.
            os.read(stdin_fd, 1)
        except OSError:
            pass
        try:
            loop.call_soon_threadsafe(task.cancel)
        except RuntimeError:
            pass

    threading.Thread(target=watch_parent, daemon=True, name="svc-mc-stop").start()
    try:
        await upload(_ObservedSource(source), url=profile["url"], profile=profile)
    except asyncio.CancelledError:
        pass
    finally:
        await asyncio.to_thread(source.close)


def run(args):
    try:
        if args.game_pid < 2 or any((args.url, args.token_file, args.ca_file)):
            raise InputError("bridge_arguments", "Use a saved profile and the local Minecraft client.")
        if args.action in ("inspect", "pair"):
            _pair_from_game(args)
        elif args.player is not None:
            raise InputError("bridge_arguments", "Only pairing uses a Minecraft player identity.")
        elif args.action == "start":
            if args.profile is None or not args.app:
                raise InputError("app_selection", "Select a profile and apps before starting.")
            if sys.stdin.isatty():
                raise InputError("bridge_stdin", "The Minecraft client must own the control pipe.")
            profile = load_profile(args.profile, args.profiles_dir)
            source = _source(args.app, args.game_pid)
            asyncio.run(_start(profile, source, sys.stdin.fileno()))
            _emit("stopped")
        elif args.profile is not None or args.app is not None:
            raise InputError("bridge_arguments", "Listing takes no app or profile argument.")
        elif args.action == "apps":
            _emit("apps", items=_apps(args.game_pid)[:128])
        else:
            _emit("profiles", items=list_profiles(args.profiles_dir)[:128])
        return 0
    except SenderError as error:
        _emit("error", code=error.code)
        return error.exit_code
    except Exception:
        _emit("error", code="internal_error")
        return 1
