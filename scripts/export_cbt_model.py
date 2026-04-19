"""
export_cbt_model.py
===================
Converts a merged Gemma 3 1B HuggingFace model → LiteRT INT4 .litertlm
for use with LiteRT-LM / LocalFallbackProvider in Lattice.

Run colab_setup.py as Cell 1 first (fresh runtime, before any imports).
Then paste or exec() this file as Cell 2.

Pipeline:
    merged HF safetensors (bf16)
        → litert_torch.generative.export_hf  → INT4-quantised .tflite
        → litert_torch.generative.utilities.litertlm_builder.build_litertlm
        → .litertlm  (drop into app/src/main/assets/)

Flags:
    --merged DIR    Path to merged HF model dir
                    (default: /content/finetune-output/merged in Colab)
    --hf-source ID  HuggingFace model ID or local path passed to export_hf
                    (default: same as --merged)
    --subfolder SF  Subfolder within the HF repo (e.g. cbt-merged-bf16)
    --output DIR    Output directory (default: /content/export-output)
    --filename NAME Output stem (default: gemma3-1b-it-cbt-int4)
    --prefill N     Prefill seq len (default: 256)
    --context N     KV cache size — must match ekv value in model filename
                    (default: 1280)
    --upload        Upload finished .litertlm to HuggingFace
    --hf-repo REPO  Destination repo (default: masked-kunsiquat/gemma-3-1b-it-litert)
"""

from __future__ import annotations

import argparse
import os
import pathlib
import subprocess
import sys
import time
import zipfile

# ── Paths ─────────────────────────────────────────────────────────────────────
_in_notebook = "__file__" not in dir()
_SCRIPT_DIR  = pathlib.Path.cwd() if _in_notebook else pathlib.Path(__file__).parent

# ╔══════════════════════════════════════════════════════════════════════════════╗
# ║  NOTEBOOK CONFIGURATION — edit these when running as a Kaggle/Colab cell   ║
# ╚══════════════════════════════════════════════════════════════════════════════╝
# HuggingFace repo ID where the merged CBT model was uploaded (required).
NOTEBOOK_HF_SOURCE  = "masked-kunsiquat/gemma-3-1b-it-litert"
# Subfolder within that repo (set to None if weights are at the root).
NOTEBOOK_SUBFOLDER  = "cbt-merged-bf16"
# Output dir on the notebook filesystem.
NOTEBOOK_OUTPUT_DIR = "/kaggle/working/export-output"
# Set True to auto-upload the finished .litertlm back to HuggingFace.
NOTEBOOK_UPLOAD     = True
# ══════════════════════════════════════════════════════════════════════════════

DEFAULT_MERGED  = (
    pathlib.Path("/content/finetune-output/merged")
    if _in_notebook else
    _SCRIPT_DIR / "finetune_cbt_lora-output" / "merged"
)
DEFAULT_OUTPUT  = (
    pathlib.Path(NOTEBOOK_OUTPUT_DIR)
    if _in_notebook else
    _SCRIPT_DIR / "export_cbt_model-output"
)
DEFAULT_FILENAME = "gemma3-1b-it-cbt-int4"
DEFAULT_HF_REPO  = "masked-kunsiquat/gemma-3-1b-it-litert"
DEFAULT_PREFILL  = 256
DEFAULT_CONTEXT  = 1280


# ─────────────────────────────────────────────────────────────────────────────
# Argument parsing
# ─────────────────────────────────────────────────────────────────────────────

def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser()
    p.add_argument("--merged",    default=None)
    p.add_argument("--hf-source",
                   default=NOTEBOOK_HF_SOURCE if _in_notebook else None,
                   help="HF model ID or local path for export_hf (defaults to --merged)")
    p.add_argument("--subfolder",
                   default=NOTEBOOK_SUBFOLDER if _in_notebook else None,
                   help="Subfolder within HF repo (e.g. cbt-merged-bf16)")
    p.add_argument("--output",    default=str(DEFAULT_OUTPUT))
    p.add_argument("--filename",  default=DEFAULT_FILENAME)
    p.add_argument("--prefill",   type=int, default=DEFAULT_PREFILL)
    p.add_argument("--context",   type=int, default=DEFAULT_CONTEXT)
    p.add_argument("--upload",    action="store_true",
                   default=NOTEBOOK_UPLOAD if _in_notebook else False)
    p.add_argument("--hf-repo",   default=DEFAULT_HF_REPO)
    args, _ = p.parse_known_args()
    return args


# ─────────────────────────────────────────────────────────────────────────────
# HuggingFace auth
# ─────────────────────────────────────────────────────────────────────────────

def hf_login() -> None:
    token = os.environ.get("HF_TOKEN")
    if not token and _in_notebook:
        # Colab
        try:
            from google.colab import userdata
            token = userdata.get("HF_TOKEN")
        except Exception:
            pass
    if not token and _in_notebook:
        # Kaggle
        try:
            from kaggle_secrets import UserSecretsClient
            token = UserSecretsClient().get_secret("HF_TOKEN")
        except Exception:
            pass
    if not token:
        print("[warn] No HF_TOKEN found — gated model download and upload will fail.")
        print("  Colab: add HF_TOKEN to Colab Secrets (key icon in sidebar)")
        print("  Kaggle: Notebook settings → Secrets → Add HF_TOKEN → Attach to notebook")
        return
    from huggingface_hub import login
    login(token=token, add_to_git_credential=False)
    print("HuggingFace: authenticated")


# ─────────────────────────────────────────────────────────────────────────────
# torchao stub injection
# ─────────────────────────────────────────────────────────────────────────────

def _inject_torchao_stubs() -> None:
    """
    Installs a Python 3.12-compatible sys.meta_path hook that auto-stubs any
    torchao.quantization.pt2e.* import before litert_torch loads.

    Uses find_spec / create_module / exec_module (the modern MetaPathFinder
    protocol). The old find_module / load_module API was silently dropped in
    Python 3.12, which is why the previous approach did nothing on Kaggle.

    Safe to call after colab_setup.py — the hook checks sys.meta_path first.
    Must be called before any 'import litert_torch' statement.
    """
    import importlib.abc
    import importlib.util
    import types
    from unittest.mock import MagicMock

    needs_stub = False
    try:
        from torchao.quantization.pt2e.graph_utils import find_sequential_partitions  # noqa: F401
    except (ImportError, RuntimeError, AttributeError):
        needs_stub = True

    if not needs_stub:
        return

    # Drop stale/partial pt2e entries so the hook starts with a clean slate
    for key in list(sys.modules.keys()):
        if key == "torchao.quantization.pt2e" or key.startswith("torchao.quantization.pt2e."):
            del sys.modules[key]

    class _Pt2eAutoStub(importlib.abc.MetaPathFinder, importlib.abc.Loader):
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
            def _getattr(name):
                cls = _make_cls(name)
                setattr(module, name, cls)
                return cls
            module.__getattr__ = _getattr

    # Only install if colab_setup.py hasn't already done so
    if not any(isinstance(f, importlib.abc.MetaPathFinder)
               and type(f).__name__ == "_Pt2eAutoStub"
               for f in sys.meta_path):
        sys.meta_path.insert(0, _Pt2eAutoStub())

    import importlib as _il
    _gu = _il.import_module("torchao.quantization.pt2e.graph_utils")
    _qt = _il.import_module("torchao.quantization.pt2e.quantizer")
    _gu.find_sequential_partitions = lambda *a, **kw: None
    _qt.QuantizationAnnotation = type("QuantizationAnnotation", (), {
        "__class_getitem__": classmethod(lambda c, *a, **kw: c),
    })
    _qt.QuantizationSpec = type("QuantizationSpec", (), {
        "__class_getitem__": classmethod(lambda c, *a, **kw: c),
        "with_args":         classmethod(lambda c, *a, **kw: c),
    })

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


_inject_torchao_stubs()


# ─────────────────────────────────────────────────────────────────────────────
# Dependency check
# ─────────────────────────────────────────────────────────────────────────────

def check_deps() -> None:
    missing = []
    try:
        import litert_torch  # noqa: F401
    except Exception as e:
        missing.append(f"litert-torch>=0.8.0 (import failed: {e})")
    try:
        from litert_torch.generative.utilities.litertlm_builder import (  # noqa: F401
            build_litertlm,
        )
    except Exception as e:
        missing.append(f"litert_torch.generative (import failed: {e})")
    if missing:
        sys.exit(
            "Missing dependencies:\n  " + "\n  ".join(missing) + "\n\n"
            "Run colab_setup.py as Cell 1 first."
        )


# ─────────────────────────────────────────────────────────────────────────────
# Step 1 — export_hf → .tflite
# ─────────────────────────────────────────────────────────────────────────────

def _resolve_source(source: str, subfolder: str | None) -> str:
    """
    export_hf passes --subfolder to its own CLI but does NOT forward it to
    from_pretrained(), so HF repos with the model in a subfolder fail.
    Workaround: download just that subfolder locally and return the local path.
    """
    if not subfolder:
        return source
    from huggingface_hub import snapshot_download
    local_cache = pathlib.Path("/tmp/_hf_model_cache")
    print(f"Downloading subfolder '{subfolder}' from {source} …")
    snapshot_download(
        repo_id=source,
        allow_patterns=[f"{subfolder}/*"],
        local_dir=str(local_cache),
    )
    local_model_dir = local_cache / subfolder
    if not local_model_dir.exists():
        sys.exit(
            f"snapshot_download completed but {local_model_dir} not found.\n"
            f"Check that the subfolder '{subfolder}' exists in {source}."
        )
    print(f"  Resolved to local path: {local_model_dir}")
    return str(local_model_dir)


def run_export_hf(
    source: str,
    tflite_dir: pathlib.Path,
    prefill: int,
    context: int,
    subfolder: str | None,
) -> pathlib.Path:
    """
    Calls litert_torch.generative.export_hf as a subprocess.
    Returns the path to the produced .tflite file.

    Flags used:
        -p              prefill sequence length
        --cache_length  KV cache size (must match ekv value in .litertlm filename)
        -q dynamic_wi4_afp32   INT4 weights, fp32 activations
        --externalize_embedder reduce peak RAM during tracing
        --use_jinja_template   required for Gemma 3 chat template
    """
    # Resolve HF subfolder → local path so export_hf can find the safetensors.
    resolved_source = _resolve_source(source, subfolder)

    cmd = [
        sys.executable, "-m", "litert_torch.generative.export_hf",
        resolved_source,
        str(tflite_dir),
        "-p", str(prefill),
        "--cache_length", str(context),
        "-q", "dynamic_wi4_afp32",
        "--externalize_embedder",
        "--use_jinja_template",
    ]
    # subfolder is already baked into resolved_source — don't pass it again.

    # Write a launcher that injects torchao stubs before running export_hf,
    # so the subprocess gets the same fix as the parent process.
    launcher = pathlib.Path("/tmp/_export_hf_launcher.py")
    launcher.write_text(
        "import sys, types, importlib.abc, importlib.util, typing\n"
        "_needs_stub = False\n"
        "try:\n"
        "    from torchao.quantization.pt2e.graph_utils import find_sequential_partitions\n"
        "except (ImportError, RuntimeError, AttributeError):\n"
        "    _needs_stub = True\n"
        "if _needs_stub:\n"
        "    for _k in list(sys.modules.keys()):\n"
        "        if _k == 'torchao.quantization.pt2e' or _k.startswith('torchao.quantization.pt2e.'):\n"
        "            del sys.modules[_k]\n"
        "    def _mk(n): return type(n,(),({\n"
        "        '__class_getitem__':classmethod(lambda c,*a,**k:c),\n"
        "        'with_args':classmethod(lambda c,*a,**k:c),\n"
        "        '__init__':lambda self,*a,**k:None,\n"
        "        '__call__':lambda self,*a,**k:None}))\n"
        "    class _S(importlib.abc.MetaPathFinder, importlib.abc.Loader):\n"
        "        _P = 'torchao.quantization.pt2e'\n"
        "        def find_spec(self,n,path,target=None):\n"
        "            return importlib.util.spec_from_loader(n,self,is_package=True) if(n==self._P or n.startswith(self._P+'.'))\\\n"
        "                else None\n"
        "        def create_module(self,spec):\n"
        "            m=types.ModuleType(spec.name);m.__path__=[];m.__package__=spec.name\n"
        "            m.__spec__=spec;m.__loader__=self\n"
        "            m.__file__=f'/stub/{spec.name.replace(\".\",\"/\")}.py';return m\n"
        "        def exec_module(self,m):\n"
        "            def _ga(n):\n"
        "                c=_mk(n);setattr(m,n,c);return c\n"
        "            m.__getattr__=_ga\n"
        "    sys.meta_path.insert(0,_S())\n"
        "    import importlib as _il\n"
        "    _il.import_module('torchao.quantization.pt2e.graph_utils').find_sequential_partitions=lambda*a,**k:None\n"
        "    _qt=_il.import_module('torchao.quantization.pt2e.quantizer')\n"
        "    _qt.QuantizationAnnotation=type('QuantizationAnnotation',(),{'__class_getitem__':classmethod(lambda c,*a,**k:c),'__init__':lambda self,*a,**k:None})\n"
        "    _qt.QuantizationSpec=type('QuantizationSpec',(),{'__class_getitem__':classmethod(lambda c,*a,**k:c),'with_args':classmethod(lambda c,*a,**k:c),'__init__':lambda self,*a,**k:None})\n"
        "    if not getattr(typing.get_type_hints,'_lp',False):\n"
        "        _og=typing.get_type_hints\n"
        "        def _sg(o,*a,**k):\n"
        "            try: return _og(o,*a,**k)\n"
        "            except (SyntaxError,AttributeError,NameError,TypeError): return getattr(o,'__annotations__',{})\n"
        "        _sg._lp=True;typing.get_type_hints=_sg\n"
        "import runpy, sys as _sys\n"
        "_sys.argv = _sys.argv[1:]\n"
        "runpy.run_module('litert_torch.generative.export_hf', run_name='__main__')\n",
        encoding="utf-8",
    )
    cmd = [sys.executable, str(launcher)] + cmd[2:]  # replace -m args with launcher

    print("Running export_hf …")
    print("  " + " ".join(cmd))
    result = subprocess.run(cmd, check=False)
    if result.returncode != 0:
        sys.exit(
            "\nexport_hf failed (see output above).\n"
            "Common fixes:\n"
            "  • Not enough RAM — use Colab High-RAM CPU runtime (Runtime → "
            "Change runtime type → CPU → High RAM)\n"
            "  • TF conflict — did you run the setup cell in a fresh runtime "
            "before importing anything?\n"
            "  • Wrong litert-torch version — "
            "pip install -U 'litert-torch>=0.8.0'"
        )

    candidates = list(tflite_dir.glob("*.tflite"))
    if not candidates:
        sys.exit(f"No .tflite produced in {tflite_dir} — check export_hf output.")
    tflite_path = candidates[0]
    size_mb = tflite_path.stat().st_size / 1e6
    print(f"  .tflite → {tflite_path.name}  ({size_mb:.0f} MB)")
    return tflite_path


# ─────────────────────────────────────────────────────────────────────────────
# Step 2 — build_litertlm
# ─────────────────────────────────────────────────────────────────────────────

def run_build_litertlm(
    tflite_path: pathlib.Path,
    model_dir: pathlib.Path,
    tflite_dir: pathlib.Path,
    out_path: pathlib.Path,
    context: int,
) -> None:
    """
    Bundles .tflite + tokenizer into a .litertlm archive.
    Searches for tokenizer.model in tflite_dir first, then model_dir.

    litert-torch 0.8.0 API (different from earlier versions):
        - llm_model_type is now a string ('gemma3'), not an enum
        - workdir (temp dir for intermediate files) is required
        - context_length (KV cache size) is required
    """
    from litert_torch.generative.utilities.litertlm_builder import build_litertlm

    # export_hf copies tokenizer.model next to the .tflite; also check the
    # local HF cache in case the download put it there.
    _local_hf_cache = pathlib.Path("/tmp/_hf_model_cache")
    tokenizer_search = [
        tflite_dir / "tokenizer.model",
        model_dir / "tokenizer.model",
        _local_hf_cache / "tokenizer.model",
    ]
    tokenizer_path = next((p for p in tokenizer_search if p.exists()), None)
    if tokenizer_path is None:
        sys.exit(
            f"tokenizer.model not found in any of: {[str(p) for p in tokenizer_search]}\n"
            "export_hf should copy it alongside the .tflite — check step 1 output."
        )

    workdir = tflite_dir / "_litertlm_work"
    workdir.mkdir(exist_ok=True)

    print("Building .litertlm …")
    build_litertlm(
        tflite_model_path=str(tflite_path),
        workdir=str(workdir),
        output_path=str(out_path),
        context_length=context,
        tokenizer_model_path=str(tokenizer_path),
        llm_model_type="gemma3",
    )


# ─────────────────────────────────────────────────────────────────────────────
# Smoke test
# ─────────────────────────────────────────────────────────────────────────────

def smoke_test(out_path: pathlib.Path) -> bool:
    """
    Validates the .litertlm bundle structure.
    Full engine validation requires an Android device — not possible here.
    """
    print("Smoke test …")
    if not zipfile.is_zipfile(str(out_path)):
        print("  [FAIL] not a valid zip archive — bundle is corrupt")
        return False
    with zipfile.ZipFile(str(out_path)) as zf:
        names = zf.namelist()
    has_tflite    = any(n.endswith(".tflite") for n in names)
    has_tokenizer = any("tokenizer" in n for n in names)
    if has_tflite and has_tokenizer:
        print(f"  [OK] .tflite + tokenizer present ({len(names)} entries total)")
        return True
    print(f"  [WARN] unexpected contents: {names}")
    return False


# ─────────────────────────────────────────────────────────────────────────────
# Upload
# ─────────────────────────────────────────────────────────────────────────────

def upload(out_path: pathlib.Path, repo_id: str) -> None:
    from huggingface_hub import HfApi
    print(f"\nUploading {out_path.name} → {repo_id} …")
    api = HfApi()
    api.upload_file(
        path_or_fileobj=str(out_path),
        path_in_repo=out_path.name,
        repo_id=repo_id,
        repo_type="model",
        commit_message=f"Add CBT fine-tuned LiteRT INT4: {out_path.name}",
    )
    print(f"Done → https://huggingface.co/{repo_id}/blob/main/{out_path.name}")
    print("Next: populate MODEL_SHA256[MODEL_FILE_CBT] in LocalFallbackProvider.")


# ─────────────────────────────────────────────────────────────────────────────
# Main
# ─────────────────────────────────────────────────────────────────────────────

def main() -> None:
    args = parse_args()

    check_deps()
    hf_login()

    output_dir  = pathlib.Path(args.output)
    tflite_dir  = output_dir / "_tflite_tmp"
    output_dir.mkdir(parents=True, exist_ok=True)
    tflite_dir.mkdir(parents=True, exist_ok=True)

    model_dir   = pathlib.Path(args.merged) if args.merged else DEFAULT_MERGED
    hf_source   = args.hf_source or str(model_dir)
    out_path    = output_dir / f"{args.filename}.litertlm"

    print(f"\nSource : {hf_source}")
    print(f"Output : {out_path}")
    print(f"Prefill: {args.prefill}   Context: {args.context} tokens\n")

    if not model_dir.exists() and not args.hf_source:
        sys.exit(
            f"Model dir not found: {model_dir}\n"
            "Pass --merged <path> or --hf-source <hf_model_id>"
        )

    t0 = time.time()

    # Step 1
    tflite_path = run_export_hf(
        source=hf_source,
        tflite_dir=tflite_dir,
        prefill=args.prefill,
        context=args.context,
        subfolder=args.subfolder,
    )

    # Step 2
    run_build_litertlm(
        tflite_path=tflite_path,
        model_dir=model_dir,
        tflite_dir=tflite_dir,
        out_path=out_path,
        context=args.context,
    )

    elapsed = time.time() - t0
    size_mb = out_path.stat().st_size / 1e6
    print(f"\nDone in {elapsed / 60:.1f} min — {out_path.name} ({size_mb:.0f} MB)")

    smoke_test(out_path)

    if args.upload:
        upload(out_path, args.hf_repo)
    else:
        print(
            f"\nNext:\n"
            f"  Upload:  rerun with --upload\n"
            f"  Or CLI:  huggingface-cli upload {args.hf_repo} {out_path}\n"
            f"  Then:    populate MODEL_SHA256[MODEL_FILE_CBT] in LocalFallbackProvider"
        )


if __name__ == "__main__" or _in_notebook:
    main()
