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
# litert_torch imports torchao.quantization.pt2e which was removed in torchao
# ~0.5+. We never call the PT2E quantizer — it just gets imported and crashes.
#
# Uses the Python 3.4+ MetaPathFinder protocol (find_spec / create_module /
# exec_module). The old find_module / load_module API was removed in Python
# 3.12, which is what Kaggle and recent Colab runtimes use — so the previous
# hook silently did nothing on those platforms.
#
# Must happen before any 'import litert_torch' anywhere in this session.
import importlib.abc
import importlib.util
import types
from unittest.mock import MagicMock

_torchao_broken = False
try:
    from torchao.quantization.pt2e.graph_utils import find_sequential_partitions  # noqa: F401
except (ImportError, RuntimeError, AttributeError):
    _torchao_broken = True

if _torchao_broken:
    print("\nApplying torchao stubs (pt2e submodule missing or broken) …")

    # Drop any stale/partial pt2e entries so the hook starts with a clean slate
    for _k in list(sys.modules.keys()):
        if _k == "torchao.quantization.pt2e" or _k.startswith("torchao.quantization.pt2e."):
            del sys.modules[_k]

    class _Pt2eAutoStub(importlib.abc.MetaPathFinder, importlib.abc.Loader):
        """
        Modern (Python 3.4+) meta-path hook.  Auto-creates a stub ModuleType
        for every torchao.quantization.pt2e.* import — no matter how many
        sub-levels deep litert_torch goes — so none of them ever raise
        ModuleNotFoundError or 'not a package'.
        """
        _PREFIX = "torchao.quantization.pt2e"

        def find_spec(self, fullname, path, target=None):
            if fullname == self._PREFIX or fullname.startswith(self._PREFIX + "."):
                return importlib.util.spec_from_loader(fullname, self,
                                                       is_package=True)
            return None

        def create_module(self, spec):
            mod = types.ModuleType(spec.name)
            mod.__path__ = []
            mod.__package__ = spec.name
            mod.__spec__ = spec
            mod.__loader__ = self
            return mod

        def exec_module(self, module):
            # Any attribute not explicitly set returns a MagicMock, so calls
            # like HistogramObserver.with_args(...) work without error.
            module.__getattr__ = lambda name: MagicMock()

    sys.meta_path.insert(0, _Pt2eAutoStub())

    # Pre-populate the specific names litert_torch actually accesses
    import importlib as _il
    _graph_utils = _il.import_module("torchao.quantization.pt2e.graph_utils")
    _quantizer   = _il.import_module("torchao.quantization.pt2e.quantizer")
    _graph_utils.find_sequential_partitions = MagicMock()
    _quantizer.QuantizationAnnotation = MagicMock()
    _quantizer.QuantizationSpec = MagicMock()

    print("  Import hook installed (Python 3.12-compatible find_spec API).")
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
