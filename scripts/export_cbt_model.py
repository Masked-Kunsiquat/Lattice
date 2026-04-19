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

DEFAULT_MERGED  = (
    pathlib.Path("/content/finetune-output/merged")
    if _in_notebook else
    _SCRIPT_DIR / "finetune_cbt_lora-output" / "merged"
)
DEFAULT_OUTPUT  = (
    pathlib.Path("/content/export-output")
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
    p.add_argument("--hf-source", default=None,
                   help="HF model ID or local path for export_hf (defaults to --merged)")
    p.add_argument("--subfolder", default=None,
                   help="Subfolder within HF repo (e.g. cbt-merged-bf16)")
    p.add_argument("--output",    default=str(DEFAULT_OUTPUT))
    p.add_argument("--filename",  default=DEFAULT_FILENAME)
    p.add_argument("--prefill",   type=int, default=DEFAULT_PREFILL)
    p.add_argument("--context",   type=int, default=DEFAULT_CONTEXT)
    p.add_argument("--upload",    action="store_true")
    p.add_argument("--hf-repo",   default=DEFAULT_HF_REPO)
    args, _ = p.parse_known_args()
    return args


# ─────────────────────────────────────────────────────────────────────────────
# HuggingFace auth
# ─────────────────────────────────────────────────────────────────────────────

def hf_login() -> None:
    token = os.environ.get("HF_TOKEN")
    if not token and _in_notebook:
        try:
            from google.colab import userdata
            token = userdata.get("HF_TOKEN")
        except Exception:
            pass
    if not token:
        print("[warn] No HF_TOKEN — gated model download and upload will fail.")
        return
    from huggingface_hub import login
    login(token=token, add_to_git_credential=False)
    print("HuggingFace: authenticated")


# ─────────────────────────────────────────────────────────────────────────────
# Dependency check
# ─────────────────────────────────────────────────────────────────────────────

def check_deps() -> None:
    missing = []
    try:
        import litert_torch  # noqa: F401
    except ImportError:
        missing.append("litert-torch>=0.8.0")
    try:
        from litert_torch.generative.utilities.litertlm_builder import (  # noqa: F401
            build_litertlm, LlmModelType,
        )
    except ImportError:
        missing.append("litert-torch[generative] (generative submodule missing)")
    if missing:
        sys.exit(
            "Missing dependencies:\n  " + "\n  ".join(missing) + "\n\n"
            "Run the setup cell at the top of this script first.\n"
            "Key: uninstall tensorflow BEFORE installing litert-torch."
        )


# ─────────────────────────────────────────────────────────────────────────────
# Step 1 — export_hf → .tflite
# ─────────────────────────────────────────────────────────────────────────────

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
    cmd = [
        sys.executable, "-m", "litert_torch.generative.export_hf",
        source,
        str(tflite_dir),
        "-p", str(prefill),
        "--cache_length", str(context),
        "-q", "dynamic_wi4_afp32",
        "--externalize_embedder",
        "--use_jinja_template",
    ]
    if subfolder:
        cmd += ["--subfolder", subfolder]

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
) -> None:
    """
    Bundles .tflite + tokenizer into a .litertlm archive.
    Searches for tokenizer.model in tflite_dir first, then model_dir.
    """
    from litert_torch.generative.utilities.litertlm_builder import (
        build_litertlm, LlmModelType,
    )

    tokenizer_path = tflite_dir / "tokenizer.model"
    if not tokenizer_path.exists():
        tokenizer_path = model_dir / "tokenizer.model"
    if not tokenizer_path.exists():
        sys.exit(
            f"tokenizer.model not found in {tflite_dir} or {model_dir}.\n"
            "export_hf should copy it alongside the .tflite — check step 1 output."
        )

    print(f"Building .litertlm …")
    build_litertlm(
        tflite_model_path=str(tflite_path),
        tokenizer_model_path=str(tokenizer_path),
        output_path=str(out_path),
        llm_model_type=LlmModelType.GEMMA,
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
