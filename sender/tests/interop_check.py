"""Invoked by the Gradle interopTest fixture; all secrets stay in private build files."""
import asyncio
import base64
import json
from pathlib import Path
import subprocess
import sys
from urllib.parse import urlsplit, urlunsplit

from svc_broadcast_sender.profiles import pair, parse_pairing, parse_pairing_identity, load_profile
from svc_broadcast_sender.errors import SenderError
from svc_broadcast_sender.sources import ToneSource
from svc_broadcast_sender.transport import upload

pairing_path, directory, identity_path = map(Path, sys.argv[1:4])
player = sys.argv[4]
automatic_path, automatic_player = Path(sys.argv[5]), sys.argv[6]
blob = pairing_path.read_text()
info = parse_pairing(blob)

def encoded(data):
    return "svcb1:" + base64.urlsafe_b64encode(json.dumps(data).encode()).decode().rstrip("=")

for fault in ("ca", "address", "server"):
    changed = dict(info)
    if fault == "ca": changed["ca_pem"] = (Path(__file__).parent / "fixtures/test-ca.pem").read_text()
    if fault == "address":
        parts = urlsplit(info["url"])
        changed["url"] = urlunsplit((parts.scheme, f"localhost:{parts.port}", parts.path, "", ""))
    if fault == "server": changed["server_id"] = "00000000-0000-0000-0000-000000000001"
    try: pair("fault-" + fault, encoded(changed), "test", directory)
    except SenderError: pass
    else: raise AssertionError("untrusted pairing accepted")
pair("home", blob, "Python interop", directory)
try: pair("replay", blob, "test", directory)
except SenderError: pass
else: raise AssertionError("pairing replay accepted")
profile = load_profile("home", directory)
result = asyncio.run(upload(ToneSource(0.2), url=profile["url"], profile=profile))
assert result.sent_frames == 10
# Exercise the actual CLI without microphone/device access.
result = subprocess.run([sys.executable, "-m", "svc_broadcast_sender", "--profiles-dir", str(directory),
                         "tone", "--profile", "home", "--seconds", "0.2"], capture_output=True, text=True)
assert result.returncode == 0, "CLI profile upload failed"
assert profile["token"] not in result.stdout + result.stderr
print("PYTHON INTEROP PASS: isolated CA, address/server checks, single-use pairing, profile upload and CLI")

identity = identity_path.read_text()
assert set(parse_pairing_identity(identity)) == {"version", "server_id", "url", "ca_pem"}


def bridge(action, player_id=player, offer=None, metadata=identity):
    data = metadata + "\n" + (offer + "\n" if offer else "")
    child = subprocess.run(
        [sys.executable, "-m", "svc_broadcast_sender", "--profiles-dir", str(directory),
         "mc-bridge", action, "--profile=mc-test", "--player=" + player_id, "--game-pid", "100"],
        input=data, capture_output=True, text=True, timeout=10,
    )
    assert not child.stderr
    assert all(line not in child.stdout for line in data.splitlines()) and profile["token"] not in child.stdout
    return json.loads(child.stdout)


assert bridge("inspect") == {"event": "paired", "name": "home"}
assert bridge("inspect", automatic_player) == {"event": "pair_required"}
offer = automatic_path.read_text()
assert bridge("pair", automatic_player, offer) == {"event": "paired", "name": "mc-test"}
assert bridge("inspect", automatic_player) == {"event": "paired", "name": "mc-test"}
assert bridge("pair", automatic_player, offer) == {"event": "paired", "name": "mc-test"}
profile = load_profile("mc-test", directory)
assert profile["player_id"] == automatic_player
assert asyncio.run(upload(ToneSource(0.2), url=profile["url"], profile=profile)).sent_frames == 10
print("GAME BRIDGE INTEROP PASS: private pipe, automated HTTPS pairing, profile reuse and WSS")
