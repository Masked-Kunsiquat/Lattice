"""
colab_setup.py
==============
Run this as Cell 1 in a FRESH Colab or Kaggle notebook before anything else.
Do NOT restart after — TF was never imported, so no restart needed.

    # Cell 1 — paste and run this file
    # Cell 2 — paste and run export_cbt_model.py

Kaggle setup before running:
    1. Notebook settings → Internet → On
    2. Add Secrets → Name: HF_TOKEN, Value: your HuggingFace token → Attach to notebook
    3. Accelerator → None  (CPU, 30 GB RAM — enough for 1B model export)
"""

import subprocess
import sys


def _run(cmd: list[str], label: str) -> None:
    print(f"\n{'─'*60}")
    print(f"{label}")
    print(f"{'─'*60}")
    # Stream output so failures are visible immediately
    result = subprocess.run(cmd, text=True)
    if result.returncode != 0:
        print(f"  [warn] exit code {result.returncode} — may be non-fatal for uninstalls")


# ── 1. Remove Colab's pre-installed TF stack ──────────────────────────────────
# litert-torch ships its own ai-edge-tensorflow which conflicts with the
# system TF. Must uninstall before importing anything TF-related.
_run(
    [sys.executable, "-m", "pip", "uninstall", "-y",
     "tensorflow", "tensorflow-cpu", "tensorflow-gpu",
     "tf-keras", "keras", "tensorboard"],
    "Removing system TensorFlow",
)

# ── 2. Pin torchao BEFORE installing litert-torch ────────────────────────────
# litert-torch requires torchao.quantization.pt2e which was removed in ~0.5.x.
# Must uninstall the pre-installed version first — pip won't downgrade otherwise.
_run(
    [sys.executable, "-m", "pip", "uninstall", "-y", "torchao"],
    "Removing pre-installed torchao",
)
_run(
    [sys.executable, "-m", "pip", "install", "-q", "torchao==0.3.1"],
    "Installing torchao==0.3.1",
)
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
