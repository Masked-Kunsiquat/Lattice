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
            # Set __file__ to a real string so code that does
            # module.__file__.endswith('.py') doesn't go through __getattr__
            # and get a class back instead of a string.
            mod.__file__ = f"/stub/{spec.name.replace('.', '/')}.py"
            return mod

        def exec_module(self, module):
            # Return real empty classes (not MagicMock) for any attribute access.
            #
            # MagicMock mocks *everything*, including dunder methods. In Python
            # 3.13, typing.get_type_hints() calls isinstance(hint, str) and
            # MagicMock can satisfy that check, causing the ForwardRef evaluator
            # to treat the mock as a string annotation and raise:
            #   SyntaxError: Forward reference must be an expression -- got <MagicMock>
            # A real class is never isinstance-compatible with str, so typing
            # machinery skips it cleanly.
            mod_name = module.__name__

            def _make_cls(name):
                return type(name, (), {
                    "__class_getitem__": classmethod(lambda c, *a, **kw: c),
                    "with_args":         classmethod(lambda c, *a, **kw: c),
                    "__init__":          lambda self, *a, **kw: None,
                    "__call__":          lambda self, *a, **kw: None,
                })

            def _getattr(name):
                cls = _make_cls(name)
                setattr(module, name, cls)
                return cls

            module.__getattr__ = _getattr

    sys.meta_path.insert(0, _Pt2eAutoStub())

    # Pre-populate the specific names litert_torch actually accesses.
    # Use real classes so they survive typing.get_type_hints() without error.
    import importlib as _il

    _graph_utils = _il.import_module("torchao.quantization.pt2e.graph_utils")
    _quantizer   = _il.import_module("torchao.quantization.pt2e.quantizer")

    def _fn(*a, **kw): return None          # stand-in for find_sequential_partitions
    _graph_utils.find_sequential_partitions = _fn
    _quantizer.QuantizationAnnotation = type("QuantizationAnnotation", (), {
        "__class_getitem__": classmethod(lambda c, *a, **kw: c),
        "__init__":          lambda self, *a, **kw: None,
    })
    _quantizer.QuantizationSpec = type("QuantizationSpec", (), {
        "__class_getitem__": classmethod(lambda c, *a, **kw: c),
        "with_args":         classmethod(lambda c, *a, **kw: c),
        "__init__":          lambda self, *a, **kw: None,
    })

    # Safety net: if typing.get_type_hints() still trips on a stub type,
    # fall back to raw __annotations__ rather than propagating a SyntaxError.
    import typing as _typing
    if not getattr(_typing.get_type_hints, "_lattice_patched", False):
        _orig_gth = _typing.get_type_hints
        def _safe_gth(obj, *a, **kw):
            try:
                return _orig_gth(obj, *a, **kw)
            except (SyntaxError, AttributeError, NameError, TypeError):
                return getattr(obj, "__annotations__", {})
        _safe_gth._lattice_patched = True
        _typing.get_type_hints = _safe_gth

    print("  Import hook installed (Python 3.12-compatible find_spec API).")
else:
    print("\ntorchao OK — no stubs needed.")

# ── 4. Verify litert_torch.generative imports cleanly ────────────────────────
print(f"\n{'─'*60}")
print("Verifying litert_torch.generative …")
print(f"{'─'*60}")
try:
    import litert_torch
    import litert_torch.generative.utilities.litertlm_builder as _builder_mod
    from litert_torch.generative.utilities.litertlm_builder import build_litertlm

    # The model-type enum has been renamed across versions. Find whatever is
    # available so export_cbt_model.py knows what name to use.
    _model_type_cls = None
    for _cls_name in ("LlmModelType", "GemmaModelType", "ModelType", "LlmType"):
        if hasattr(_builder_mod, _cls_name):
            _model_type_cls = _cls_name
            break

    print(f"\n[OK] litert-torch {litert_torch.__version__} — generative submodule present")
    print("Run Cell 2 (export_cbt_model.py) now.")
except Exception as e:
    print(f"\n[FAIL] {type(e).__name__}: {e}")
    print("Check output above for the specific import that failed.")
