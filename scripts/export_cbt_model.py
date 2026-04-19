"""
export_cbt_model.py
===================
Converts a merged Gemma 3 1B HuggingFace model → LiteRT INT4 .litertlm file
ready to ship as a drop-in replacement for the Lattice app's LocalFallbackProvider.

Requires:
    pip install "litert-torch>=0.8.0" huggingface_hub

Pipeline:
    merged/   (bf16 HF safetensors)
        → litert_torch.generative.export_hf  (INT4-quantised .tflite)
        → litert_torch.generative.utilities.litertlm_builder.build_litertlm
            (bundles .tflite + tokenizer → .litertlm)

Usage (from Lattice project root, or paste into Colab):
    pip install "litert-torch>=0.8.0" huggingface_hub
    python scripts/export_cbt_model.py

Optional flags:
    --merged DIR       Path to merged HF model dir
                       (default: /content/finetune-output/merged in Colab,
                        scripts/finetune_cbt_lora-output/merged otherwise)
    --output DIR       Output directory
                       (default: /content/export-output in Colab)
    --filename NAME    Output filename without extension
                       (default: gemma3-1b-it-cbt-int4)
    --prefill N        Prefill sequence length for tracing (default: 256)
    --context N        KV cache / context window tokens (default: 1280)
    --adapter DIR      Merge a PEFT adapter on top of --base before exporting
    --base MODEL       Base HF model for --adapter mode
                       (default: google/gemma-3-1b-it)
    --upload           Upload the finished .litertlm to HuggingFace after export
    --hf-repo REPO     HF repo for upload (default: masked-kunsiquat/gemma-3-1b-it-litert)

Output:
    <output>/<filename>.litertlm  — drop into app/src/main/assets/ and it will
    be automatically preferred by LocalFallbackProvider over the base tier file.
"""

from __future__ import annotations

import argparse
import os
import pathlib
import subprocess
import sys
import time

# ── Paths ─────────────────────────────────────────────────────────────────────
_in_notebook = "__file__" not in dir()
_SCRIPT_DIR  = pathlib.Path.cwd() if _in_notebook else pathlib.Path(__file__).parent

DEFAULT_MERGED_DIR = (
    pathlib.Path("/content/finetune-output/merged")
    if _in_notebook
    else _SCRIPT_DIR / "finetune_cbt_lora-output" / "merged"
)
DEFAULT_OUTPUT_DIR = (
    pathlib.Path("/content/export-output")
    if _in_notebook
    else _SCRIPT_DIR / "export_cbt_model-output"
)
DEFAULT_BASE_MODEL = "google/gemma-3-1b-it"
DEFAULT_FILENAME   = "gemma3-1b-it-cbt-int4"
DEFAULT_HF_REPO    = "masked-kunsiquat/gemma-3-1b-it-litert"
DEFAULT_PREFILL    = 256
DEFAULT_CONTEXT    = 1280  # matches ekv1280 LiteRT kernel cache in Lattice


# ─────────────────────────────────────────────────────────────────────────────
# Argument parsing
# ─────────────────────────────────────────────────────────────────────────────

def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Export merged Gemma 3 1B to LiteRT INT4 .litertlm")
    p.add_argument("--merged",   default=None,                help="Path to merged HF model dir")
    p.add_argument("--adapter",  default=None,                help="PEFT adapter dir (alternative to --merged)")
    p.add_argument("--base",     default=DEFAULT_BASE_MODEL,  help="Base model for --adapter mode")
    p.add_argument("--output",   default=str(DEFAULT_OUTPUT_DIR), help="Output directory")
    p.add_argument("--filename", default=DEFAULT_FILENAME,    help="Output filename (no extension)")
    p.add_argument("--prefill",  type=int, default=DEFAULT_PREFILL,  help="Prefill seq len for tracing")
    p.add_argument("--context",  type=int, default=DEFAULT_CONTEXT,  help="KV cache / context window tokens")
    p.add_argument("--upload",   action="store_true",         help="Upload .litertlm to HuggingFace after export")
    p.add_argument("--hf-repo",  default=DEFAULT_HF_REPO,     help="HF repo for --upload")
    args, _ = p.parse_known_args()
    return args


# ─────────────────────────────────────────────────────────────────────────────
# HuggingFace auth
# ─────────────────────────────────────────────────────────────────────────────

def _hf_login() -> None:
    token = os.environ.get("HF_TOKEN")
    if not token and _in_notebook:
        try:
            from google.colab import userdata
            token = userdata.get("HF_TOKEN")
        except Exception:
            pass
    if token:
        try:
            from huggingface_hub import login
            login(token=token, add_to_git_credential=False)
            print("HuggingFace: authenticated via HF_TOKEN")
        except Exception as e:
            print(f"[warn] HuggingFace login failed: {e}")
    else:
        print("[warn] No HF_TOKEN found — upload will fail if --upload is set.")


# ─────────────────────────────────────────────────────────────────────────────
# Adapter merge (optional path)
# ─────────────────────────────────────────────────────────────────────────────

def _merge_adapter(base_model_id: str, adapter_dir: pathlib.Path, out_dir: pathlib.Path) -> None:
    _hf_login()
    try:
        import torch
        from transformers import AutoModelForCausalLM, AutoTokenizer
        from peft import PeftModel
    except ImportError as e:
        sys.exit(f"Missing dependency for adapter merge: {e}")

    out_dir.mkdir(parents=True, exist_ok=True)
    tokenizer = AutoTokenizer.from_pretrained(str(adapter_dir))
    base      = AutoModelForCausalLM.from_pretrained(base_model_id, torch_dtype=torch.bfloat16)
    model     = PeftModel.from_pretrained(base, str(adapter_dir))
    model     = model.merge_and_unload()
    model.save_pretrained(str(out_dir), safe_serialization=True)
    tokenizer.save_pretrained(str(out_dir))
    print(f"  Merged → {out_dir}")


def resolve_source(args: argparse.Namespace) -> pathlib.Path:
    if args.adapter:
        adapter_path = pathlib.Path(args.adapter)
        if not adapter_path.exists():
            sys.exit(f"Adapter dir not found: {adapter_path}")
        merged_path = pathlib.Path(args.output) / "_merged_tmp"
        print(f"Merging adapter {adapter_path} with base {args.base} …")
        _merge_adapter(args.base, adapter_path, merged_path)
        return merged_path

    merged_path = pathlib.Path(args.merged) if args.merged else DEFAULT_MERGED_DIR
    if not merged_path.exists():
        sys.exit(
            f"Merged model not found: {merged_path}\n"
            "Run finetune_cbt_lora.py first, or pass --merged / --adapter."
        )
    return merged_path


# ─────────────────────────────────────────────────────────────────────────────
# Export — two-step: export_hf → build_litertlm
# ─────────────────────────────────────────────────────────────────────────────

def export(args: argparse.Namespace) -> None:
    output_dir = pathlib.Path(args.output)
    output_dir.mkdir(parents=True, exist_ok=True)

    model_dir  = resolve_source(args)
    tflite_dir = output_dir / "tflite_tmp"
    tflite_dir.mkdir(parents=True, exist_ok=True)
    out_path   = output_dir / f"{args.filename}.litertlm"

    print(f"\nSource model : {model_dir}")
    print(f"Output       : {out_path}")
    print(f"Prefill len  : {args.prefill}")
    print(f"Context      : {args.context} tokens")

    # ── Dependency check ──────────────────────────────────────────────────────
    try:
        import litert_torch  # noqa: F401
        from litert_torch.generative.utilities.litertlm_builder import (
            build_litertlm,
            LlmModelType,
        )
    except ImportError as e:
        sys.exit(
            f"Missing dependency: {e}\n"
            "Install: pip install 'litert-torch>=0.8.0'\n"
            "Docs: https://github.com/google-ai-edge/litert-torch"
        )

    t0 = time.time()

    # ── Step 1: export_hf → INT4-quantised .tflite ───────────────────────────
    # Uses the litert_torch.generative.export_hf CLI. Accepts a local model
    # directory or a HuggingFace model ID. Produces model.tflite in tflite_dir.
    #
    # -q dynamic_wi4_afp32  = INT4 weights, fp32 activations (matches base tiers)
    # --use_jinja_template   = required for Gemma 3 chat template
    # --externalize_embedder = reduces peak memory during conversion
    print("\nStep 1 — exporting to .tflite (INT4) …")
    cmd = [
        sys.executable, "-m", "litert_torch.generative.export_hf",
        str(model_dir),
        str(tflite_dir),
        "-p", str(args.prefill),
        "--cache_length", str(args.context),
        "-q", "dynamic_wi4_afp32",
        "--externalize_embedder",
        "--use_jinja_template",
    ]
    result = subprocess.run(cmd, check=False)
    if result.returncode != 0:
        sys.exit(
            "export_hf failed — check output above.\n"
            "Common causes:\n"
            "  • Insufficient RAM (needs ~32 GB; use Colab High-RAM runtime)\n"
            "  • litert-torch version too old: pip install -U 'litert-torch>=0.8.0'\n"
            "  • Model dir missing config.json / tokenizer files"
        )

    # Locate the produced .tflite file
    tflite_candidates = list(tflite_dir.glob("*.tflite"))
    if not tflite_candidates:
        sys.exit(f"No .tflite found in {tflite_dir} after export_hf — check step 1 output.")
    tflite_path = tflite_candidates[0]
    print(f"  .tflite → {tflite_path}  ({tflite_path.stat().st_size / 1e6:.0f} MB)")

    # Locate tokenizer — export_hf copies it alongside the tflite, or fall back
    # to the source model dir.
    tokenizer_path = tflite_dir / "tokenizer.model"
    if not tokenizer_path.exists():
        tokenizer_path = model_dir / "tokenizer.model"
    if not tokenizer_path.exists():
        sys.exit(
            f"tokenizer.model not found in {tflite_dir} or {model_dir}.\n"
            "Gemma tokenizer files must be present alongside the model safetensors."
        )

    # ── Step 2: bundle .tflite + tokenizer → .litertlm ───────────────────────
    print(f"\nStep 2 — bundling .litertlm …")
    build_litertlm(
        tflite_model_path=str(tflite_path),
        tokenizer_model_path=str(tokenizer_path),
        output_path=str(out_path),
        llm_model_type=LlmModelType.GEMMA,
    )

    elapsed  = time.time() - t0
    size_mb  = out_path.stat().st_size / (1024 ** 2)
    print(f"\nExport complete in {elapsed / 60:.1f} min")
    print(f"Output: {out_path}  ({size_mb:.0f} MB)")

    # ── Smoke test ────────────────────────────────────────────────────────────
    # Verifies the bundle is a valid zip and contains the expected entries.
    # Full engine validation requires an Android device — not possible in Python.
    print("\nSmoke test — validating bundle structure …")
    import zipfile
    if not zipfile.is_zipfile(str(out_path)):
        print("[FAIL] .litertlm is not a valid zip archive — bundle may be corrupt.")
    else:
        with zipfile.ZipFile(str(out_path)) as zf:
            names = zf.namelist()
        has_tflite    = any(n.endswith(".tflite") for n in names)
        has_tokenizer = any("tokenizer" in n for n in names)
        if has_tflite and has_tokenizer:
            print(f"  [OK] bundle contains .tflite + tokenizer ({len(names)} entries)")
        else:
            print(f"  [WARN] unexpected bundle contents: {names}")

    # ── Upload ────────────────────────────────────────────────────────────────
    if args.upload:
        _upload(out_path, args.hf_repo)
    else:
        print(
            f"\nNext steps:\n"
            f"  1. Review a few reframes with the merged model (smoke-test cell)\n"
            f"  2. Upload: python scripts/export_cbt_model.py --upload\n"
            f"     (or: huggingface-cli upload {args.hf_repo} {out_path})\n"
            f"  3. Populate MODEL_SHA256[MODEL_FILE_CBT] in LocalFallbackProvider\n"
            f"  4. Copy {out_path.name} into app/src/main/assets/ for local testing"
        )


# ─────────────────────────────────────────────────────────────────────────────
# HuggingFace upload
# ─────────────────────────────────────────────────────────────────────────────

def _upload(litertlm_path: pathlib.Path, repo_id: str) -> None:
    _hf_login()
    try:
        from huggingface_hub import HfApi
    except ImportError:
        sys.exit("Missing huggingface_hub: pip install huggingface_hub")

    print(f"\nUploading {litertlm_path.name} → {repo_id} …")
    api = HfApi()
    api.upload_file(
        path_or_fileobj=str(litertlm_path),
        path_in_repo=litertlm_path.name,
        repo_id=repo_id,
        repo_type="model",
        commit_message=f"Add CBT fine-tuned LiteRT INT4 model: {litertlm_path.name}",
    )
    print(f"Uploaded → https://huggingface.co/{repo_id}/blob/main/{litertlm_path.name}")
    print("Populate MODEL_SHA256[MODEL_FILE_CBT] in LocalFallbackProvider with the file's SHA-256.")


# ─────────────────────────────────────────────────────────────────────────────
# Entry point
# ─────────────────────────────────────────────────────────────────────────────

if __name__ == "__main__" or _in_notebook:
    args = parse_args()
    export(args)
