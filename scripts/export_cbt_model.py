"""
export_cbt_model.py
===================
Converts a merged Gemma 3 1B HuggingFace model → LiteRT INT4 .litertlm file
ready to ship as a drop-in replacement for the Lattice app's LocalFallbackProvider.

Requires the AI Edge Torch package from Google:
    pip install ai-edge-torch

Pipeline:
    merged/   (bf16 HF safetensors)
        → ai_edge_torch quantize (INT4 weight-only)
        → export_tflite (produces .litertlm)

Usage (from Lattice project root):
    pip install ai-edge-torch
    python scripts/export_cbt_model.py

Optional flags:
    --merged DIR       Path to merged model directory (default: auto-detect)
    --output DIR       Output directory (default: scripts/export_cbt_model-output)
    --filename NAME    Output filename without extension (default: gemma3-1b-it-cbt-int4)
    --context N        Context window tokens (default: 1280 — matches ekv1280 kernels)
    --adapter DIR      Use an adapter dir + base model instead of a pre-merged dir
    --base MODEL       Base model for --adapter mode (default: google/gemma-3-1b-it)

Output:
    <output>/<filename>.litertlm   — drop into app/src/main/assets/ to replace the
                                     existing tier file that LocalFallbackProvider loads

After export, update LocalFallbackProvider to prefer the CBT model file.
"""

from __future__ import annotations

import argparse
import os
import pathlib
import sys
import time

# ── Paths ─────────────────────────────────────────────────────────────────────
_in_notebook = "__file__" not in dir()
_SCRIPT_DIR  = pathlib.Path.cwd() if _in_notebook else pathlib.Path(__file__).parent

DEFAULT_MERGED_DIR  = _SCRIPT_DIR / "finetune_cbt_lora-output" / "merged"
DEFAULT_ADAPTER_DIR = _SCRIPT_DIR / "finetune_cbt_lora-output" / "adapter"
DEFAULT_OUTPUT_DIR  = _SCRIPT_DIR / "export_cbt_model-output"
DEFAULT_BASE_MODEL  = "google/gemma-3-1b-it"
DEFAULT_FILENAME    = "gemma3-1b-it-cbt-int4"
DEFAULT_CONTEXT     = 1280   # matches ekv1280 LiteRT kernel cache


# ─────────────────────────────────────────────────────────────────────────────
# Argument parsing
# ─────────────────────────────────────────────────────────────────────────────

def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Export merged Gemma 3 1B to LiteRT INT4")
    p.add_argument("--merged",   default=None,            help="Path to merged HF model dir")
    p.add_argument("--adapter",  default=None,            help="Path to PEFT adapter dir (alternative to --merged)")
    p.add_argument("--base",     default=DEFAULT_BASE_MODEL, help="Base model for --adapter mode")
    p.add_argument("--output",   default=str(DEFAULT_OUTPUT_DIR), help="Output directory")
    p.add_argument("--filename", default=DEFAULT_FILENAME, help="Output filename (no extension)")
    p.add_argument("--context",  type=int, default=DEFAULT_CONTEXT, help="Context window tokens")
    args, _ = p.parse_known_args()
    return args


# ─────────────────────────────────────────────────────────────────────────────
# Resolve source model path
# ─────────────────────────────────────────────────────────────────────────────

def resolve_source(args: argparse.Namespace) -> pathlib.Path:
    """
    Returns the directory with the model to export.
    - If --adapter is given: load base + adapter, merge, write to a temp dir, return that.
    - If --merged is given: use directly.
    - Otherwise: fall back to DEFAULT_MERGED_DIR.
    """
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
    print(f"  Merged to {out_dir}")


# ─────────────────────────────────────────────────────────────────────────────
# Export
# ─────────────────────────────────────────────────────────────────────────────

def export(args: argparse.Namespace) -> None:
    output_dir = pathlib.Path(args.output)
    output_dir.mkdir(parents=True, exist_ok=True)

    model_dir = resolve_source(args)
    out_path  = output_dir / f"{args.filename}.litertlm"

    print(f"\nSource model : {model_dir}")
    print(f"Output       : {out_path}")
    print(f"Context      : {args.context} tokens")

    try:
        import torch
        import ai_edge_torch
        from ai_edge_torch.generative.layers import kv_cache as kv_cache_lib
        from ai_edge_torch.generative.quantize import quant_recipe
        from ai_edge_torch.generative.utilities import loader as model_loader
        from transformers import AutoModelForCausalLM, AutoTokenizer
    except ImportError as e:
        sys.exit(
            f"Missing dependency: {e}\n"
            "Install: pip install ai-edge-torch\n"
            "Docs: https://github.com/google-ai-edge/ai-edge-torch"
        )

    print("\nLoading model for export …")
    tokenizer = AutoTokenizer.from_pretrained(str(model_dir))
    model     = AutoModelForCausalLM.from_pretrained(
        str(model_dir),
        torch_dtype=torch.bfloat16,
        device_map="cpu",    # export runs on CPU
    )
    model.eval()

    print("Quantizing (INT4 weight-only) …")
    quant_config = quant_recipe.GenerativeQuantRecipe(
        default=quant_recipe.QuantizationScope(
            op_sets=[quant_recipe.QuantizedOperationType.LINEAR],
            algorithm=quant_recipe.EmbeddingQuantizationAlgorithm.MIN_MAX_UNIFORM_QUANTIZE,
            dtype=quant_recipe.QuantizationDtype.INT4,
        )
    )

    print("Exporting to LiteRT …")
    t0 = time.time()

    # Dummy inputs for tracing — single token prefill at context length
    prompt_tokens = args.context
    input_ids       = torch.zeros((1, prompt_tokens), dtype=torch.long)
    attention_mask  = torch.ones((1, prompt_tokens),  dtype=torch.long)

    edge_model = ai_edge_torch.convert(
        model,
        sample_inputs=(input_ids, attention_mask),
        quant_config=quant_config,
    )
    edge_model.export(str(out_path))

    elapsed = time.time() - t0
    size_mb = out_path.stat().st_size / (1024 ** 2)
    print(f"\nExport complete in {elapsed/60:.1f} min")
    print(f"Output: {out_path}  ({size_mb:.0f} MB)")
    print(
        "\nNext steps:\n"
        f"  1. Copy {out_path.name} into app/src/main/assets/\n"
        "  2. Update LocalFallbackProvider to load the CBT model file\n"
        "  3. Run ./gradlew assembleDebug and test on device"
    )


# ─────────────────────────────────────────────────────────────────────────────
# Entry point
# ─────────────────────────────────────────────────────────────────────────────

if __name__ == "__main__" or _in_notebook:
    args = parse_args()
    export(args)
