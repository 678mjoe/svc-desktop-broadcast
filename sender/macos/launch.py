"""Start the bundled sender with its own dependencies."""
from pathlib import Path
import sys

APP = Path(__file__).resolve().parent
sys.path[:0] = [str(APP), str(APP / "vendor")]

from svc_broadcast_sender.cli import main

raise SystemExit(main())
