"""
export_cbt_model.py — Kaggle "The Beast" Edition
===============================================
- Cleans up /tmp and /working directories first
- Patches Gemma 3 Config (RoPE bypass)
- Recovers tokenizer.model from Google
- Quantizes to INT8 (dynamic_wi8)
- Bundles and Uploads to HF
"""

from __future__ import annotations
import argparse, os, pathlib, sys, gc, shutil, runpy, json
from huggingface_hub import hf_hub_download, snapshot_download, HfApi

# ── Step 0: The Janitor (Cleanup) ───────────────────────────────────────────

def cleanup_environment(output_dir, cache_dir):
    """Wipes stale files to prevent 'Disk Full' or 'File Exists' errors."""
    print("🧹 Cleaning environment...")

    # Paths to clear
    to_wipe = [
        output_dir,
        cache_dir,
        pathlib.Path("/tmp/_tflite_tmp"),
        pathlib.Path("/tmp/_litertlm_work")
    ]

    safe_bases = [pathlib.Path("/tmp"), pathlib.Path("/kaggle/working")]

    for path in to_wipe:
        p = pathlib.Path(path).absolute()

        # Validation
        if p == pathlib.Path("/"):
            print(f"  ⚠️ Skipping root directory: {p}")
            continue
        if p == pathlib.Path.home():
            print(f"  ⚠️ Skipping home directory: {p}")
            continue
        if p.is_symlink():
            print(f"  ⚠️ Skipping symlink: {p}")
            continue

        is_under_safe_base = any(p == base or base in p.parents for base in safe_bases)
        if not is_under_safe_base:
            print(f"  ⚠️ Skipping path outside safe bases (/tmp, /kaggle/working): {p}")
            continue

        if p.exists():
            if not p.is_dir():
                print(f"  ⚠️ Skipping non-directory: {p}")
                continue
            print(f"  Removing: {p}")
            shutil.rmtree(p)
        p.mkdir(parents=True, exist_ok=True)

    # Trigger garbage collection
    gc.collect()

# ── Step 1: Logic Helpers ───────────────────────────────────────────────────

def patch_config(model_dir):
    cfg_p = pathlib.Path(model_dir) / "config.json"
    if not cfg_p.exists():
        return
    with open(cfg_p, "r") as f:
        cfg = json.load(f)
    if "rope_scaling" in cfg:
        print("🛠️ Patching config: Removing rope_scaling...")
        del cfg["rope_scaling"]
        with open(cfg_p, "w") as f:
            json.dump(cfg, f, indent=2)

def recover_tokenizer(model_dir):
    target = pathlib.Path(model_dir) / "tokenizer.model"
    if target.exists():
        return target
    print("🔍 tokenizer.model missing! Fetching compatible version from Google...")
    try:
        path = hf_hub_download(repo_id="google/gemma-3-1b-it", filename="tokenizer.model", local_dir=model_dir)
        return pathlib.Path(path)
    except Exception as e:
        raise RuntimeError(f"❌ Tokenizer recovery failed: {e}") from e

# ── Step 2: The Process ──────────────────────────────────────────────────────

def run_pipeline(args):
    out_dir = pathlib.Path(args.output)
    tmp_dir = out_dir / "_tflite_tmp"
    cache_dir = pathlib.Path("/kaggle/working/_hf_cache")

    # 0. Clean start
    cleanup_environment(out_dir, cache_dir)
    # Re-create tmp_dir because cleanup wiped the whole out_dir
    tmp_dir.mkdir(parents=True, exist_ok=True)

    # 1. Download Model
    print(f"📥 Resolving source: {args.hf_source}")
    snapshot_download(
        repo_id=args.hf_source,
        allow_patterns=[f"{args.subfolder}/*"] if args.subfolder else None,
        local_dir=str(cache_dir)
    )
    model_path = cache_dir / args.subfolder if args.subfolder else cache_dir

    # 2. Patch & Tokenizer
    patch_config(model_path)
    tok_path = recover_tokenizer(model_path)

    # 3. Export HF to TFLite
    orig_argv = sys.argv
    sys.argv = [
        "litert_torch.generative.export_hf", str(model_path), str(tmp_dir),
        "-p", f"[{args.prefill}]", "--cache_length", str(args.context),
        "-q", "dynamic_wi8_afp32", "--externalize_embedder", "--use_jinja_template"
    ]

    print("🚀 Starting Export (In-Process)...")
    try:
        runpy.run_module("litert_torch.generative.export_hf", run_name="__main__")
    finally:
        sys.argv = orig_argv

    # 4. Find the main model file
    tflite_files = list(tmp_dir.glob("*.tflite"))
    if not tflite_files:
        raise RuntimeError(f"Export finished but NO .tflite files found in {tmp_dir}")
    main_tflite = max(tflite_files, key=lambda p: p.stat().st_size)

    # 5. The Bundle
    bundle_work = tmp_dir / "bundle_work"
    bundle_work.mkdir(parents=True, exist_ok=True)
    final_file = out_dir / f"{args.filename}.litertlm"
    print(f"📦 Bundling into: {final_file}")

    from litert_torch.generative.utilities.litertlm_builder import build_litertlm
    build_litertlm(
        tflite_model_path=str(main_tflite),
        workdir=str(bundle_work),
        output_path=str(out_dir),
        context_length=args.context,
        tokenizer_model_path=str(tok_path),
        llm_model_type="gemma3"
    )

    generated_file = out_dir / (main_tflite.stem + ".litertlm")
    if generated_file.exists() and generated_file != final_file:
        generated_file.rename(final_file)

    if not final_file.exists():
        raise RuntimeError(
            f"❌ LiteRT-LM bundle failed to materialize at {final_file}. "
            "Check build_litertlm() logs for errors."
        )

    return final_file

# ── Main ─────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--hf-source", default="masked-kunsiquat/gemma-3-1b-it-litert")
    parser.add_argument("--subfolder", default="cbt-merged-bf16")
    parser.add_argument("--output",    default="/kaggle/working/export-output")
    parser.add_argument("--filename",  default="gemma3-1b-it-cbt-int8")
    parser.add_argument("--prefill",   type=int, default=256)
    parser.add_argument("--context",   type=int, default=1280)
    parser.add_argument("--upload",    action="store_true", default=False, help="Upload to HF repo if successful")
    args, _ = parser.parse_known_args()

    result_path = run_pipeline(args)

    if result_path and result_path.exists():
        print(f"✨ SUCCESS! Created: {result_path}")
        if args.upload:
            api = HfApi()
            print("📤 Uploading to HF...")
            api.upload_file(path_or_fileobj=str(result_path), path_in_repo=result_path.name, repo_id=args.hf_source)
        else:
            print("ℹ️ Upload skipped (use --upload to publish)")
