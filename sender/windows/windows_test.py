"""Explicit Windows test menu; importing it never opens audio or a connection."""
from contextlib import redirect_stdout, redirect_stderr
from datetime import datetime
import importlib
import json
import os
from pathlib import Path
import platform
import struct
import sys
import tempfile

APP = Path(__file__).resolve().parent
ROOT = APP.parent
sys.path.insert(0, str(APP / "vendor"))
sys.path.insert(0, str(APP))
PROFILE = "hangzhou-win"
BUNDLE_VERSION = "0.3.0-win-test2"
if (APP / "svc-wasapi-check.exe").is_file():
    BUNDLE_VERSION = "p4d-win-mc-test2" if (ROOT / "README-MINECRAFT.md").is_file() else "p4c-win-live-test3"
DLL_HANDLES = [os.add_dll_directory(str(ROOT / "runtime"))] if os.name == "nt" else []


class Tee:
    def __init__(self, console, log): self.console, self.log = console, log
    def write(self, value):
        self.console.write(value); self.log.write(value); self.log.flush()
        return len(value)
    def flush(self): self.console.flush(); self.log.flush()


def logged(action, operation):
    """Only controlled diagnostics; pairing and hidden input never use this function."""
    directory = ROOT / "logs"
    directory.mkdir(exist_ok=True)
    path = directory / (datetime.now().strftime("%Y%m%d-%H%M%S-%f-") + action + ".log")
    with path.open("w", encoding="utf-8") as log:
        with redirect_stdout(Tee(sys.stdout, log)), redirect_stderr(Tee(sys.stderr, log)):
            print(f"Windows test bundle {BUNDLE_VERSION}; action={action}")
            result = operation()
            print("exit_code=" + str(result))
    print("诊断日志：" + str(path))
    return result


def verify_private_acl(path):
    """Check the real Windows ACL, including the current user's SID; never log the SID."""
    import ctypes as c
    from ctypes import wintypes as w
    adv = c.WinDLL("advapi32", use_last_error=True)
    kernel = c.WinDLL("kernel32", use_last_error=True)
    adv.GetNamedSecurityInfoW.argtypes = [w.LPWSTR, c.c_int, w.DWORD, c.POINTER(c.c_void_p), c.c_void_p,
                                        c.POINTER(c.c_void_p), c.c_void_p, c.POINTER(c.c_void_p)]
    adv.GetNamedSecurityInfoW.restype = w.DWORD
    adv.GetSecurityDescriptorControl.argtypes = [c.c_void_p, c.POINTER(w.WORD), c.POINTER(w.DWORD)]
    adv.GetAclInformation.argtypes = [c.c_void_p, c.c_void_p, w.DWORD, c.c_int]
    adv.GetAce.argtypes = [c.c_void_p, w.DWORD, c.POINTER(c.c_void_p)]
    adv.OpenProcessToken.argtypes = [w.HANDLE, w.DWORD, c.POINTER(w.HANDLE)]
    adv.GetTokenInformation.argtypes = [w.HANDLE, c.c_int, c.c_void_p, w.DWORD, c.POINTER(w.DWORD)]
    adv.EqualSid.argtypes = [c.c_void_p, c.c_void_p]
    kernel.GetCurrentProcess.restype = w.HANDLE
    kernel.CloseHandle.argtypes = [w.HANDLE]
    kernel.LocalFree.argtypes = [c.c_void_p]
    descriptor, owner, acl, ace = (c.c_void_p() for _ in range(4))
    token = w.HANDLE()
    try:
        if adv.GetNamedSecurityInfoW(str(path), 1, 5, c.byref(owner), None, c.byref(acl), None, c.byref(descriptor)):
            raise OSError
        control, revision = w.WORD(), w.DWORD()
        if not adv.GetSecurityDescriptorControl(descriptor, c.byref(control), c.byref(revision)) or not control.value & 0x1000:
            raise OSError
        size_info = (w.DWORD * 3)()
        if not acl or not adv.GetAclInformation(acl, size_info, c.sizeof(size_info), 2) or size_info[0] != 1:
            raise OSError
        if not adv.GetAce(acl, 0, c.byref(ace)) or c.cast(ace, c.POINTER(c.c_ubyte))[0] != 0:
            raise OSError
        if not adv.OpenProcessToken(kernel.GetCurrentProcess(), 8, c.byref(token)): raise OSError
        needed = w.DWORD()
        adv.GetTokenInformation(token, 1, None, 0, c.byref(needed))
        user = c.create_string_buffer(needed.value)
        if not adv.GetTokenInformation(token, 1, user, needed, c.byref(needed)): raise OSError
        sid = c.cast(user, c.POINTER(c.c_void_p))[0]
        if not adv.EqualSid(owner, sid) or not adv.EqualSid(c.c_void_p(ace.value + 8), sid): raise OSError
        mask = c.c_uint32.from_address(ace.value + 4).value
        if mask & 0x1F01FF != 0x1F01FF: raise OSError
    finally:
        if token: kernel.CloseHandle(token)
        if descriptor: kernel.LocalFree(descriptor)


def private_storage_check():
    from svc_broadcast_sender.profiles import default_directory, _private
    parent = default_directory().parent
    parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".windows-self-check-", dir=parent) as temporary:
        root = Path(temporary); _private(root, directory=True); verify_private_acl(root)
        file = root / "probe.txt"; file.write_text("non-secret permission probe", encoding="utf-8")
        _private(file); verify_private_acl(file)
        link = root / "atomic-probe.txt"
        os.link(file, link)  # Same publication primitive as real profile storage.
        if link.read_text(encoding="utf-8") != "non-secret permission probe": raise OSError


def self_check():
    print(json.dumps({"system": platform.system(), "release": platform.release(),
                      "machine": platform.machine(), "python": platform.python_version(),
                      "bits": struct.calcsize("P") * 8}, ensure_ascii=False))
    if os.name != "nt" or struct.calcsize("P") != 8 or sys.version_info[:2] != (3, 12):
        print("此包只用于 Windows 11 x64 / Python 3.12。")
        return 2
    for name in ("numpy", "soxr", "_cffi_backend", "sounddevice", "websockets", "ssl"):
        try: importlib.import_module(name)
        except Exception as error:
            print("依赖加载失败：" + name + " / " + type(error).__name__)
            return 2
        print("依赖加载通过：" + name)
    try: private_storage_check()
    except Exception as error:
        print("私有配置档 ACL / 原子保存检查失败：" + type(error).__name__)
        print("先停止配对，回传这份自检日志。不要降低目录权限。")
        return 2
    print("自检通过：依赖和当前用户私有存储可用。没有打开音频、连接服务器或录音。")
    return 0


def choose_device():
    from svc_broadcast_sender.capture import list_devices
    from svc_broadcast_sender.errors import InputError
    devices = [d for d in list_devices() if d.input_channels > 0]
    for index, device in enumerate(devices, 1):
        print(f"{index}. {json.dumps(device.name, ensure_ascii=False)} | {device.hostapi} | 输入 {device.input_channels} 声道")
    print("请选择 OBS 监听送入的 CABLE Output，优先 Windows WASAPI；不要选择麦克风、立体声混音或 Minecraft 回放。")
    value = input("输入序号（回车取消）：").strip()
    if not value: return None
    if not value.isdecimal() or not 1 <= int(value) <= len(devices): raise InputError("device_choice", "设备序号无效。")
    device = devices[int(value) - 1]
    channels = 2 if device.input_channels >= 2 else 1
    rate = 48000 if device.default_samplerate not in (44100, 48000) else int(device.default_samplerate)
    print(f"将读取 {json.dumps(device.name, ensure_ascii=False)} / {device.hostapi}，{rate} Hz，{channels} 声道，发送器增益 0 dB。")
    return ["--device", device.name, "--host-api", device.hostapi, "--sample-rate", str(rate),
            "--channels", str(channels), "--gain-db", "0"]


def confirmed(message):
    print(message)
    return input("确认后输入 START（回车取消）：").strip() == "START"


def choose_apps():
    from svc_broadcast_sender.errors import InputError
    from svc_broadcast_sender.wasapi import list_apps
    import re

    apps = list_apps()
    if not apps:
        print("没有找到可选应用。打开播放器窗口后返回菜单重试。")
        return None
    for index, app in enumerate(apps, 1):
        hint = "活动音频会话" if app.active_session else "仅检测到应用窗口"
        print(f"{index}. {app.label}（{hint}，PID {app.pid}）")
    value = input("输入 1–4 个应用序号，用逗号分隔（回车取消）：").strip()
    if not value:
        return None
    parts = [part.strip() for part in value.split(",")]
    if not 1 <= len(parts) <= 4 or any(not re.fullmatch(r"[0-9]{1,3}", part) for part in parts):
        raise InputError("wasapi_selection", "请输入 1–4 个不同的应用序号。")
    indices = [int(part) for part in parts]
    if any(not 1 <= index <= len(apps) for index in indices) or len(set(indices)) != len(indices):
        raise InputError("wasapi_selection", "请输入 1–4 个不同的应用序号。")
    selected = [apps[index - 1] for index in indices]
    print("已选应用：" + "、".join(f"{app.label} (PID {app.pid})" for app in selected))
    return [value for app in selected for value in ("--app", app.selector)]


def choose_profile():
    from svc_broadcast_sender.profiles import list_profiles

    names = list_profiles()
    if not names:
        print("没有已配对的配置档。请先选择 3。")
        return None
    for index, name in enumerate(names, 1):
        print(f"{index}. {name}")
    value = input("选择服务器配置档序号（回车取消）：").strip()
    if not value.isdecimal() or len(value) > len(str(len(names))) or not 1 <= int(value) <= len(names):
        return None
    return names[int(value) - 1]


def menu():
    from svc_broadcast_sender import cli
    from svc_broadcast_sender.errors import SenderError
    print(f"SVC Windows 11 x64 测试包 {BUNDLE_VERSION}")
    print("默认不采集、不连接、不广播。先自检，再配对和本机检查。")
    while True:
        print("\n1 自检  2 列出设备  3 游戏配对  4 本机输入检查（10秒）")
        print("5 广播测试音（1秒）  6 开始音乐广播  7 列出配置档  0 退出")
        if BUNDLE_VERSION in ("p4c-win-live-test3", "p4d-win-mc-test2"):
            print("8 原生应用音源检查（30秒）  9 原生应用音源直播")
        try:
            choice = input("选择：").strip()
            if choice == "0": return 0
            if choice == "1": logged("self-check", self_check)
            elif choice == "2": logged("devices", lambda: cli.main(["devices"]))
            elif choice == "3":
                print("请先在正确的服务器中执行 /svcbroadcast pair，点击复制；随后在隐藏输入中粘贴，不要发到聊天。")
                cli.main(["pair", "--name", PROFILE, "--label", "Windows11-x64"])
            elif choice == "7": cli.main(["profiles", "list"])
            elif choice in ("8", "9") and BUNDLE_VERSION in ("p4c-win-live-test3", "p4d-win-mc-test2"):
                apps = choose_apps()
                if apps is None:
                    continue
                if choice == "8":
                    if confirmed("将检查所选应用的混合电平，不连接服务器。"):
                        logged("check-apps", lambda: cli.main(["check-apps", *apps, "--seconds", "30"]))
                else:
                    profile = choose_profile()
                    if profile is not None and confirmed("将混合所选应用并向该服务器直播，Ctrl+C 停止。"):
                        logged("broadcast-apps", lambda: cli.main(["broadcast-apps", "--profile", profile, *apps]))
            elif choice in ("4", "6"):
                device = choose_device()
                if device is None: continue
                if choice == "4":
                    if confirmed("将读取上述明确选择的输入10秒，仅本机诊断，不上传、不回放、不保存音频。"):
                        logged("check-input", lambda: cli.main(["check-input", *device, "--seconds", "10"]))
                elif confirmed("将持续广播上述输入。请确认只含你选择的播放器，并已安排测试听众。Ctrl+C 停止；暂停播放器不会断开。"):
                    logged("capture", lambda: cli.main(["capture", "--profile", PROFILE, *device]))
            elif choice == "5":
                if confirmed("将向服务器广播1秒低音量测试音，不打开输入设备。需要其他玩家收听，本人自动排除回传。"):
                    logged("tone", lambda: cli.main(["tone", "--profile", PROFILE, "--seconds", "1"]))
            else: print("请输入菜单中的序号。")
        except SenderError as error: print(error.code + ": " + error.message)
        except KeyboardInterrupt: print("\n已取消；没有自动重试。")
        except EOFError: return 0
        except Exception as error: print("操作失败：" + type(error).__name__ + "；可先运行自检并回传日志。")


if __name__ == "__main__":
    if sys.argv[1:2] == ["--cli"]:
        from svc_broadcast_sender.cli import main
        raise SystemExit(main(sys.argv[2:]))
    raise SystemExit(menu())
