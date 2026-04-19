"""
colab_setup.py
==============
Run this as Cell 1 in a FRESH Colab or Kaggle notebook before anything else.
Do NOT restart after running this cell.

    # Cell 1 — paste and run this file
    # Cell 2 — paste and run export_cbt_model.py

Kaggle setup before running:
    1. Notebook settings → Internet → On
    2. Add Secrets → Name: HF_TOKEN → Attach to notebook
    3. Accelerator → None  (CPU, 30 GB RAM)

Colab setup:
    1. Runtime → Change runtime type → CPU
    2. Secrets → Add HF_TOKEN
"""

import subprocess
import sys


def _run(cmd: list[str], label: str) -> None:
    print(f"\n{'─'*60}")
    print(label)
    print(f"{'─'*60}")
    result = subprocess.run(cmd, text=True)
    if result.returncode != 0:
        print(f"  [warn] exit code {result.returncode}")


# ── 1. Remove pre-installed TF if present (Colab has it, Kaggle does not) ────
_run(
    [sys.executable, "-m", "pip", "uninstall", "-y",
     "tensorflow", "tensorflow-cpu", "tensorflow-gpu",
     "tf-keras", "keras", "tensorboard"],
    "Removing system TensorFlow (no-op if not installed)",
)

# ── 2. Install litert-torch ───────────────────────────────────────────────────
# Do NOT try to pin torchao — Kaggle/Colab bake it into the system image and
# pip cannot cleanly replace it. Instead we stub the broken imports at runtime
# (see step 3). litert-torch itself installs fine regardless of torchao version.
_run(
    [sys.executable, "-m", "pip", "install", "-q",
     "litert-torch>=0.8.0", "huggingface_hub"],
    "Installing litert-torch + huggingface_hub",
)

# ── 3. Stub broken torchao imports before litert_torch loads ─────────────────
# litert_torch.quantize imports torchao.quantization.pt2e which was removed in
# torchao ~0.5+. We never call the PT2E quantizer directly — it just gets
# imported and crashes.
#
# Why an import hook instead of static sys.modules entries:
#   MagicMock objects lack __path__, so Python refuses to treat them as packages.
#   Any import of torchao.quantization.pt2e.quantizer.utils (or deeper) fails
#   with "is not a package". An import hook intercepts every torchao.quantization.pt2e.*
#   import — however deep — and returns a real ModuleType with __path__ = [],
#   which Python accepts as a package.
#
# This must happen before any 'import litert_torch' anywhere in this session.
import types
from unittest.mock import MagicMock

# Only stub if pt2e is broken
_torchao_broken = False
try:
    from torchao.quantization.pt2e.graph_utils import find_sequential_partitions  # noqa: F401
except (ImportError, RuntimeError, AttributeError):
    _torchao_broken = True

if _torchao_broken:
    print("\nApplying torchao stubs (pt2e submodule missing or broken) …")

    # Remove any stale/partial pt2e entries so the hook starts clean
    for _k in list(sys.modules.keys()):
        if _k == "torchao.quantization.pt2e" or _k.startswith("torchao.quantization.pt2e."):
            del sys.modules[_k]

    class _Pt2eAutoStub:
        """
        sys.meta_path finder+loader that auto-creates a stub ModuleType for any
        torchao.quantization.pt2e.* import so litert_torch's import chain never
        hits a 'not a package' error, regardless of how many sub-levels it imports.
        """
        _PREFIX = "torchao.quantization.pt2e"

        def find_module(self, fullname, path=None):  # noqa: D401
            if fullname == self._PREFIX or fullname.startswith(self._PREFIX + "."):
                return self
            return None

        def load_module(self, fullname):
            if fullname in sys.modules:
                return sys.modules[fullname]
            mod = types.ModuleType(fullname)
            mod.__path__ = []        # marks it as a package
            mod.__package__ = fullname
            mod.__loader__ = self
            mod.__spec__ = None
            sys.modules[fullname] = mod
            return mod

    sys.meta_path.insert(0, _Pt2eAutoStub())

    # Pre-populate the specific attributes litert_torch actually accesses
    import importlib as _il
    _graph_utils = _il.import_module("torchao.quantization.pt2e.graph_utils")
    _quantizer   = _il.import_module("torchao.quantization.pt2e.quantizer")
    _graph_utils.find_sequential_partitions = MagicMock()
    _quantizer.QuantizationAnnotation = MagicMock()
    _quantizer.QuantizationSpec = MagicMock()

    print("  Import hook installed (auto-stubs any torchao.quantization.pt2e.* import).")
else:
    print("\ntorchao OK — no stubs needed.")

# ── 4. Verify litert_torch.generative imports cleanly ────────────────────────
print(f"\n{'─'*60}")
print("Verifying litert_torch.generative …")
print(f"{'─'*60}")
try:
    import litert_torch
    from litert_torch.generative.utilities.litertlm_builder import (
        build_litertlm,
        LlmModelType,
    )
    print(f"\n[OK] litert-torch {litert_torch.__version__} — generative submodule present")
    print("Run Cell 2 (export_cbt_model.py) now.")
except Exception as e:
    print(f"\n[FAIL] {type(e).__name__}: {e}")
    print("Check output above for the specific import that failed.")
