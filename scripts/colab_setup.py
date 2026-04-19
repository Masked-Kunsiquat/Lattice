"""
colab_setup.py
==============
Run this as Cell 1 in a FRESH Colab notebook before anything else.
Do NOT restart after — tensorflow was never imported, so no restart needed.

    # Cell 1
    exec(open("colab_setup.py").read())   # if mounted from Drive
    # — or paste this file directly —

    # Cell 2
    exec(open("export_cbt_model.py").read())
"""

import subprocess
import sys


def _run(cmd: list[str], label: str) -> None:
    print(f"{label} …")
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0 and result.stderr:
        # Non-fatal — uninstalls may warn if package wasn't present
        print(f"  [warn] {result.stderr.strip()[:200]}")
    else:
        print(f"  OK")


# ── 1. Remove Colab's pre-installed TF stack ──────────────────────────────────
# litert-torch ships its own ai-edge-tensorflow which conflicts with the
# system TF. Must uninstall before importing anything TF-related.
_run(
    [sys.executable, "-m", "pip", "uninstall", "-y",
     "tensorflow", "tensorflow-cpu", "tensorflow-gpu",
     "tf-keras", "keras", "tensorboard"],
    "Removing system TensorFlow",
)

# ── 2. Install litert-torch + huggingface_hub ─────────────────────────────────
_run(
    [sys.executable, "-m", "pip", "install", "-q",
     "litert-torch>=0.8.0", "huggingface_hub", "peft", "transformers"],
    "Installing litert-torch + deps",
)

# ── 3. Verify generative submodule is present ─────────────────────────────────
import importlib.util
spec = importlib.util.find_spec("litert_torch.generative")
if spec is None:
    print("\n[FAIL] litert_torch.generative not found.")
    print("  Try: pip install -U 'litert-torch[generative]>=0.8.0'")
else:
    import litert_torch
    print(f"\n[OK] litert-torch {litert_torch.__version__} — generative submodule present")
    print("Run Cell 2 (export_cbt_model.py) now.")
