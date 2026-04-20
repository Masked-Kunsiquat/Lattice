"""
export_cbt_model.py
===================
Fixed for Kaggle: Runs export_hf in-process via runpy to avoid os.fork deadlocks
while maintaining all stubbing and environment logic.
"""

from __future__ import annotations

import argparse
import os
import pathlib
import sys
import time
import zipfile
import gc
import torch
import runpy
import importlib.abc
import importlib.util
import types
import typing

# ── Environment Setup ─────────────────────────────────────────────────────────

# Force cleanup
gc.collect()
if torch.cuda.is_available():
    torch.cuda.empty_cache()

_in_notebook = "__file__" not in dir()

# ╔══════════════════════════════════════════════════════════════════════════════╗
# ║  NOTEBOOK CONFIGURATION                                                      ║
# ╚══════════════════════════════════════════════════════════════════════════════╝
NOTEBOOK_HF_SOURCE  = "masked-kunsiquat/gemma-3-1b-it-litert"
NOTEBOOK_SUBFOLDER  = "cbt-merged-bf16"
NOTEBOOK_OUTPUT_DIR = "/kaggle/working/export-output"
NOTEBOOK_UPLOAD      = True
# ══════════════════════════════════════════════════════════════════════════════

DEFAULT_OUTPUT  = pathlib.Path(NOTEBOOK_OUTPUT_DIR) if _in_notebook else pathlib.Path.cwd() / "export-output"
DEFAULT_FILENAME = "gemma3-1b-it-cbt-int4"

# ── Torchao Stub Injection (The "Beast" Tamer) ───────────────────────────────

def _inject_torchao_stubs():
    """
    Prevents litert-torch from crashing when it looks for torchao.
    Must be called before importing or running litert modules.
    """
    class _Pt2eAutoStub(importlib.abc.MetaPathFinder, importlib.abc.Loader):
        _PREFIX = "torchao.quantization.pt2e"
        def find_spec(self, fullname, path, target=None):
            if fullname == self._PREFIX or fullname.startswith(self._PREFIX + "."):
                return importlib.util.spec_from_loader(fullname, self, is_package=True)
            return None
        def create_module(self, spec):
            mod = types.ModuleType(spec.name)
            mod.__path__ = []
            mod.__package__ = spec.name
            mod.__spec__ = spec
            mod.__loader__ = self
            mod.__file__ = f"/stub/{spec.name.replace('.', '/')}.py"
            return mod
        def exec_module(self, module):
            def _make_cls(name):
                return type(name, (), {
                    "__class_getitem__": classmethod(lambda c, *a, **kw: c),
                    "with_args":         classmethod(lambda c, *a, **kw: c),
                    "__init__":          lambda self, *a, **kw: None,
                    "__call__":          lambda self, *a, **kw: None,
                })
            module.__getattr__ = lambda name: _make_cls(name)

    if not any(isinstance(f, _Pt2eAutoStub) for f in sys.meta_path):
        sys.meta_path.insert(0, _Pt2eAutoStub())

    # Patch typing hints for compatibility
    if not getattr(typing.get_type_hints, "_lattice_patched", False):
        _orig_gth = typing.get_type_hints
        def _safe_gth(obj, *a, **kw):
            try: return _orig_gth(obj, *a, **kw)
            except: return getattr(obj, "__annotations__", {})
        _safe_gth._lattice_patched = True
        typing.get_type_hints = _safe_gth

# ── Logic Blocks ─────────────────────────────────────────────────────────────

def _resolve_source(source: str, subfolder: str | None) -> tuple[str, pathlib.Path | None]:
    if not subfolder:
        return source, None
    from huggingface_hub import snapshot_download
    # Using /kaggle/working to avoid RAM-disk pressure in /tmp
    local_cache = pathlib.Path("/kaggle/working/_hf_model_cache")
    print(f"Resolving HF subfolder: {source}/{subfolder}")
    snapshot_download(
        repo_id=source,
        allow_patterns=[f"{subfolder}/*"],
        local_dir=str(local_cache),
    )
    return str(local_cache / subfolder), (local_cache / subfolder)

def run_export_hf(source, tflite_dir, prefill, context, subfolder):
    resolved_source, local_model_dir = _resolve_source(source, subfolder)

    # We prepare the sys.argv exactly as the CLI expects
    orig_argv = sys.argv
    sys.argv = [
        "litert_torch.generative.export_hf",
        resolved_source,
        str(tflite_dir),
        "-p", f"[{prefill}]",
        "--cache_length", str(context),
        "-q", "dynamic_wi4_afp32",
        "--externalize_embedder",
        "--use_jinja_template",
    ]

    print(f"Running export_hf in-process via runpy...")
    try:
        # runpy.run_module is the key: it avoids fork() but runs the module logic
        runpy.run_module("litert_torch.generative.export_hf", run_name="__main__")
    except SystemExit as e:
        if e.code != 0:
            raise RuntimeError(f"export_hf failed with exit code {e.code}")
    finally:
        sys.argv = orig_argv

    candidates = list(tflite_dir.glob("*.tflite"))
    if not candidates:
        raise FileNotFoundError(f"No .tflite produced in {tflite_dir}")
    return candidates[0], local_model_dir

def run_build_litertlm(tflite_path, tflite_dir, out_path, context, local_model_dir):
    from litert_torch.generative.utilities.litertlm_builder import build_litertlm

    search_paths = [
        tflite_dir / "tokenizer.model",
        local_model_dir / "tokenizer.model" if local_model_dir else None
    ]
    tokenizer_path = next((p for p in search_paths if p and p.exists()), None)

    if not tokenizer_path:
        raise FileNotFoundError(f"Could not find tokenizer.model in {search_paths}")

    workdir = tflite_dir / "_litertlm_work"
    workdir.mkdir(exist_ok=True)

    print(f"Building .litertlm: {out_path.name}")
    build_litertlm(
        tflite_model_path=str(tflite_path),
        workdir=str(workdir),
        output_path=str(out_path),
        context_length=context,
        tokenizer_model_path=str(tokenizer_path),
        llm_model_type="gemma3",
    )

# ── Main Execution ────────────────────────────────────────────────────────────

def main():
    _inject_torchao_stubs()

    # Auth
    from huggingface_hub import login
    token = os.environ.get("HF_TOKEN")
    if not token and _in_notebook:
        try:
            from kaggle_secrets import UserSecretsClient
            token = UserSecretsClient().get_secret("HF_TOKEN")
        except: pass
    if token:
        login(token=token)

    # Args
    p = argparse.ArgumentParser()
    p.add_argument("--hf-source", default=NOTEBOOK_HF_SOURCE)
    p.add_argument("--subfolder", default=NOTEBOOK_SUBFOLDER)
    p.add_argument("--output",    default=str(DEFAULT_OUTPUT))
    p.add_argument("--filename",  default=DEFAULT_FILENAME)
    p.add_argument("--prefill",   type=int, default=256)
    p.add_argument("--context",   type=int, default=1280)
    p.add_argument("--upload",    action="store_true", default=NOTEBOOK_UPLOAD)
    args, _ = p.parse_known_args()

    out_dir = pathlib.Path(args.output)
    tmp_dir = out_dir / "_tflite_tmp"
    out_dir.mkdir(parents=True, exist_ok=True)
    tmp_dir.mkdir(parents=True, exist_ok=True)

    final_bundle_path = out_dir / f"{args.filename}.litertlm"

    # Step 1: Export
    tflite_path, local_dir = run_export_hf(
        args.hf_source, tmp_dir, args.prefill, args.context, args.subfolder
    )

    # Step 2: Bundle
    run_build_litertlm(tflite_path, tmp_dir, final_bundle_path, args.context, local_dir)

    print(f"\n✅ Export Complete: {final_bundle_path}")

    if args.upload:
        from huggingface_hub import HfApi
        print(f"Uploading to HF: {args.hf_source}")
        HfApi().upload_file(
            path_or_fileobj=str(final_bundle_path),
            path_in_repo=final_bundle_path.name,
            repo_id=args.hf_source
        )

if __name__ == "__main__":
    main()