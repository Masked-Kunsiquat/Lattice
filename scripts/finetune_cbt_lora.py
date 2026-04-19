"""
finetune_cbt_lora.py
====================
LoRA fine-tuning for the CBT reframing adapter on Gemma 3 1B Instruct.

Reads  : scripts/curate_cbt_training_data-output/gemma_ft_data.jsonl
Writes : scripts/finetune_cbt_lora-output/
           checkpoint-<step>/   — HuggingFace PEFT checkpoints
           merged/              — full merged model (ready for LiteRT INT4 export)
           run_stats.json       — loss curve + hyperparameters

The model is trained with QLoRA (4-bit base, LoRA adapters on Q/V projections).
After training, peft.merge_and_unload() produces a full bf16 merged model.
The next step (export_cbt_model.py) converts merged/ → LiteRT INT4.

Architecture note: LiteRT-LM 0.10.x has no on-device adapter loading API.
The only viable path is offline merge → LiteRT INT4 export → ship new .litertlm file.

Usage (from Lattice project root, GPU recommended):
    pip install transformers peft bitsandbytes accelerate datasets tqdm
    python scripts/finetune_cbt_lora.py

Optional flags:
    --data PATH         Path to gemma_ft_data.jsonl (default: auto-detect)
    --output DIR        Output directory (default: scripts/finetune_cbt_lora-output)
    --model MODEL       Base model ID (default: google/gemma-3-1b-it)
    --epochs N          Number of epochs (default: 3)
    --batch N           Per-device train batch size (default: 2)
    --grad-accum N      Gradient accumulation steps (default: 8)
    --lr LR             Peak learning rate (default: 2e-4)
    --max-seq N         Max sequence length in tokens (default: 512)
    --lora-r N          LoRA rank (default: 16)
    --lora-alpha N      LoRA alpha (default: 32)
    --lora-dropout F    LoRA dropout (default: 0.05)
    --no-merge          Skip post-training merge (keep adapter only)
    --resume PATH       Resume from a checkpoint directory
    --dry-run           Validate data loading only; do not train

Colab one-liner:
    !pip install -q transformers peft bitsandbytes accelerate datasets tqdm
    # then paste this script or run it from Drive
"""

from __future__ import annotations

import argparse
import json
import math
import os
import pathlib
import sys
import time
from dataclasses import dataclass, asdict
from typing import Optional

# ── Paths ─────────────────────────────────────────────────────────────────────
_in_notebook = "__file__" not in dir()
_SCRIPT_DIR  = pathlib.Path.cwd() if _in_notebook else pathlib.Path(__file__).parent

DEFAULT_DATA_PATH   = (
    pathlib.Path("/content/output/gemma_ft_data.jsonl")
    if _in_notebook
    else _SCRIPT_DIR / "output" / "gemma_ft_data.jsonl"
)
DEFAULT_OUTPUT_DIR  = (
    pathlib.Path("/content/finetune-output")
    if _in_notebook
    else _SCRIPT_DIR / "finetune_cbt_lora-output"
)

# ── Defaults ──────────────────────────────────────────────────────────────────
DEFAULT_MODEL       = "google/gemma-3-1b-it"
DEFAULT_EPOCHS      = 3
DEFAULT_BATCH       = 2
DEFAULT_GRAD_ACCUM  = 8
DEFAULT_LR          = 2e-4
DEFAULT_MAX_SEQ     = 512
DEFAULT_LORA_R      = 16
DEFAULT_LORA_ALPHA  = 32
DEFAULT_LORA_DROP   = 0.05

# ── Gemma chat template tokens ────────────────────────────────────────────────
BOS = "<bos>"
TURN_USER_START  = "<start_of_turn>user\n"
TURN_USER_END    = "<end_of_turn>\n"
TURN_MODEL_START = "<start_of_turn>model\n"
TURN_MODEL_END   = "<end_of_turn>"


# ─────────────────────────────────────────────────────────────────────────────
# Argument parsing
# ─────────────────────────────────────────────────────────────────────────────

def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="CBT LoRA fine-tuning for Gemma 3 1B")
    p.add_argument("--data",         default=str(DEFAULT_DATA_PATH), help="gemma_ft_data.jsonl path")
    p.add_argument("--output",       default=str(DEFAULT_OUTPUT_DIR), help="Output directory")
    p.add_argument("--model",        default=DEFAULT_MODEL, help="Base model HF ID or local path")
    p.add_argument("--epochs",       type=int,   default=DEFAULT_EPOCHS)
    p.add_argument("--batch",        type=int,   default=DEFAULT_BATCH)
    p.add_argument("--grad-accum",   type=int,   default=DEFAULT_GRAD_ACCUM)
    p.add_argument("--lr",           type=float, default=DEFAULT_LR)
    p.add_argument("--max-seq",      type=int,   default=DEFAULT_MAX_SEQ)
    p.add_argument("--lora-r",       type=int,   default=DEFAULT_LORA_R)
    p.add_argument("--lora-alpha",   type=int,   default=DEFAULT_LORA_ALPHA)
    p.add_argument("--lora-dropout", type=float, default=DEFAULT_LORA_DROP)
    p.add_argument("--no-merge",     action="store_true", help="Skip merge step")
    p.add_argument("--resume",       default=None, help="Resume from checkpoint dir")
    p.add_argument("--dry-run",      action="store_true", help="Data check only")
    args, _ = p.parse_known_args()  # ignore Jupyter/Colab kernel flags
    return args


# ─────────────────────────────────────────────────────────────────────────────
# Data loading
# ─────────────────────────────────────────────────────────────────────────────

def load_jsonl(path: pathlib.Path) -> list[dict]:
    records = []
    with open(path, encoding="utf-8") as f:
        for i, line in enumerate(f):
            line = line.strip()
            if not line:
                continue
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError as e:
                print(f"[warn] skipping malformed line {i+1}: {e}")
    return records


def validate_record(rec: dict, idx: int) -> bool:
    """Returns True if record has required Gemma template fields."""
    text = rec.get("text", "")
    if not text:
        print(f"[warn] record {idx} has no 'text' field — skipping")
        return False
    if TURN_MODEL_START not in text:
        print(f"[warn] record {idx} missing model turn — skipping")
        return False
    return True


def split_train_eval(records: list[dict], eval_frac: float = 0.05, seed: int = 42):
    """Deterministic 95/5 split — small eval set is enough to watch loss."""
    import random as _random
    rng = _random.Random(seed)
    shuffled = list(records)
    rng.shuffle(shuffled)
    n_eval = max(1, math.floor(len(shuffled) * eval_frac))
    return shuffled[n_eval:], shuffled[:n_eval]


# ─────────────────────────────────────────────────────────────────────────────
# Tokenization
# ─────────────────────────────────────────────────────────────────────────────

def tokenize_and_mask(examples: dict, tokenizer, max_seq: int) -> dict:
    """
    Tokenize the full Gemma conversation text.
    Only compute loss on the model turn — mask the user prompt tokens with -100.

    The model turn starts immediately after TURN_MODEL_START and includes TURN_MODEL_END.
    We find the boundary by re-tokenizing the prompt-only prefix and masking up to that length.
    """
    texts    = examples["text"]
    input_ids_list    = []
    attention_mask_list = []
    labels_list       = []

    for text in texts:
        # Find where the model response begins
        model_start_idx = text.rfind(TURN_MODEL_START)
        if model_start_idx == -1:
            # Malformed — mask everything (no loss)
            prefix = text
        else:
            prefix = text[: model_start_idx + len(TURN_MODEL_START)]

        # Tokenize prefix to get the boundary token count
        prefix_ids = tokenizer(
            prefix,
            add_special_tokens=False,
            truncation=True,
            max_length=max_seq,
        )["input_ids"]
        prefix_len = len(prefix_ids)

        # Tokenize full text
        # (guard against prefix overflowing the full sequence — checked below)
        full = tokenizer(
            text,
            add_special_tokens=False,
            truncation=True,
            max_length=max_seq,
            padding="max_length",
        )
        ids  = full["input_ids"]
        mask = full["attention_mask"]

        # Skip examples where the prompt fills or overflows max_seq — all labels
        # would be -100 and the row contributes no training signal.
        if prefix_len >= len(ids):
            continue

        # Build labels: -100 for prompt tokens, real ids for response tokens
        labels = [-100] * prefix_len + ids[prefix_len:]
        # Mask padding too
        labels = [
            lab if (mask[i] == 1 and ids[i] != tokenizer.pad_token_id) else -100
            for i, lab in enumerate(labels)
        ]

        input_ids_list.append(ids)
        attention_mask_list.append(mask)
        labels_list.append(labels)

    return {
        "input_ids":      input_ids_list,
        "attention_mask": attention_mask_list,
        "labels":         labels_list,
    }


# ─────────────────────────────────────────────────────────────────────────────
# LoRA config
# ─────────────────────────────────────────────────────────────────────────────

def build_lora_config(args: argparse.Namespace):
    from peft import LoraConfig, TaskType
    return LoraConfig(
        task_type=TaskType.CAUSAL_LM,
        r=args.lora_r,
        lora_alpha=args.lora_alpha,
        lora_dropout=args.lora_dropout,
        # Target Q and V projections — standard for Gemma / LLaMA-family models
        target_modules=["q_proj", "v_proj"],
        bias="none",
        inference_mode=False,
    )


# ─────────────────────────────────────────────────────────────────────────────
# Training
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class RunStats:
    model: str
    epochs: int
    batch_size: int
    grad_accum: int
    effective_batch: int
    lr: float
    max_seq: int
    lora_r: int
    lora_alpha: int
    lora_dropout: float
    train_examples: int
    eval_examples: int
    trainable_params: int
    total_params: int
    steps_per_epoch: int
    total_steps: int
    loss_history: list[dict]   # [{step, train_loss, eval_loss?}]
    wall_seconds: float
    merged: bool
    output_dir: str


def _hf_login() -> None:
    """
    Authenticate with HuggingFace.
    Priority:
      1. HF_TOKEN env var (set externally or via `huggingface-cli login`)
      2. Colab user secrets (key: HF_TOKEN)
    Silently skips if already logged in or no token is available.
    """
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
        print("[warn] No HF_TOKEN found — download will fail for gated models.")
        print("       Set HF_TOKEN env var or add it as a Colab secret.")


def train(args: argparse.Namespace) -> None:
    import torch

    _hf_login()

    # ── Lazy imports (keeps startup fast for --dry-run) ──────────────────────
    try:
        from transformers import AutoTokenizer, AutoModelForCausalLM, BitsAndBytesConfig
        from peft import get_peft_model, PeftModel
        from datasets import Dataset
    except ImportError as e:
        sys.exit(
            f"Missing dependency: {e}\n"
            "Run: pip install transformers peft bitsandbytes accelerate datasets"
        )

    output_dir = pathlib.Path(args.output)
    output_dir.mkdir(parents=True, exist_ok=True)

    # ── Load & validate data ──────────────────────────────────────────────────
    data_path = pathlib.Path(args.data)
    if not data_path.exists():
        sys.exit(f"Data file not found: {data_path}")

    print(f"Loading data from {data_path} …")
    raw = load_jsonl(data_path)
    valid = [r for i, r in enumerate(raw) if validate_record(r, i)]
    print(f"  {len(valid)} / {len(raw)} records passed validation")

    if args.dry_run:
        print("[dry-run] Data OK — exiting before model load.")
        return

    if len(valid) < 2:
        sys.exit(f"Too few valid records ({len(valid)}) to produce train and eval splits. Check data file.")

    train_records, eval_records = split_train_eval(valid)
    print(f"  Train: {len(train_records)}   Eval: {len(eval_records)}")

    # ── Tokenizer ─────────────────────────────────────────────────────────────
    print(f"\nLoading tokenizer: {args.model}")
    tokenizer = AutoTokenizer.from_pretrained(args.model)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    # ── Tokenize ──────────────────────────────────────────────────────────────
    print("Tokenizing …")
    train_ds = Dataset.from_list(train_records)
    eval_ds  = Dataset.from_list(eval_records)

    tok_fn = lambda ex: tokenize_and_mask(ex, tokenizer, args.max_seq)
    train_ds = train_ds.map(tok_fn, batched=True, remove_columns=train_ds.column_names)
    eval_ds  = eval_ds.map(tok_fn,  batched=True, remove_columns=eval_ds.column_names)
    train_ds.set_format("torch")
    eval_ds.set_format("torch")

    # ── Model (QLoRA: 4-bit base) ─────────────────────────────────────────────
    device = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"\nDevice: {device}")
    print(f"Loading base model: {args.model}")

    quant_config = None
    if device == "cuda":
        quant_config = BitsAndBytesConfig(
            load_in_4bit=True,
            bnb_4bit_quant_type="nf4",
            bnb_4bit_compute_dtype=torch.bfloat16,
            bnb_4bit_use_double_quant=True,
        )

    model = AutoModelForCausalLM.from_pretrained(
        args.model,
        quantization_config=quant_config,
        device_map="auto" if device == "cuda" else None,
        torch_dtype=torch.bfloat16 if device == "cuda" else torch.float32,
        attn_implementation="eager",  # flash_attention_2 optional if installed
    )
    model.config.use_cache = False

    # ── LoRA ──────────────────────────────────────────────────────────────────
    lora_cfg = build_lora_config(args)
    model     = get_peft_model(model, lora_cfg)

    trainable, total = model.get_nb_trainable_parameters()
    print(f"Trainable: {trainable:,} / {total:,} params  ({100*trainable/total:.2f}%)")

    # ── Training args ─────────────────────────────────────────────────────────
    try:
        from transformers import TrainingArguments, Trainer, DataCollatorForSeq2Seq
    except ImportError as e:
        sys.exit(f"Missing transformers: {e}")

    steps_per_epoch = math.ceil(len(train_ds) / (args.batch * args.grad_accum))
    total_steps     = steps_per_epoch * args.epochs

    # Save a checkpoint every epoch
    save_steps = steps_per_epoch
    eval_steps = steps_per_epoch

    training_args = TrainingArguments(
        output_dir=str(output_dir),
        num_train_epochs=args.epochs,
        per_device_train_batch_size=args.batch,
        per_device_eval_batch_size=args.batch,
        gradient_accumulation_steps=args.grad_accum,
        learning_rate=args.lr,
        lr_scheduler_type="cosine",
        warmup_ratio=0.05,
        fp16=False,
        bf16=(device == "cuda"),
        optim="paged_adamw_8bit" if device == "cuda" else "adamw_torch",
        logging_steps=max(1, total_steps // 50),
        eval_strategy="steps",
        eval_steps=eval_steps,
        save_strategy="steps",
        save_steps=save_steps,
        save_total_limit=2,
        load_best_model_at_end=True,
        metric_for_best_model="eval_loss",
        greater_is_better=False,
        report_to="none",
        resume_from_checkpoint=args.resume,
        dataloader_num_workers=0,
    )

    data_collator = DataCollatorForSeq2Seq(
        tokenizer=tokenizer,
        model=model,
        label_pad_token_id=-100,
        pad_to_multiple_of=8,
    )

    loss_history: list[dict] = []

    class LossCallback:
        """Captures per-step training and eval loss."""
        from transformers import TrainerCallback as _CB

        class _Inner(_CB):
            def __init__(self, history):
                self._history = history

            def on_log(self, _args, state, _control, logs=None, **kwargs):
                if logs is None:
                    return
                entry = {"step": state.global_step}
                if "loss" in logs:
                    entry["train_loss"] = round(logs["loss"], 4)
                if "eval_loss" in logs:
                    entry["eval_loss"] = round(logs["eval_loss"], 4)
                if len(entry) > 1:
                    self._history.append(entry)

    trainer = Trainer(
        model=model,
        args=training_args,
        train_dataset=train_ds,
        eval_dataset=eval_ds,
        data_collator=data_collator,
        callbacks=[LossCallback._Inner(loss_history)],
    )

    # ── Train ─────────────────────────────────────────────────────────────────
    print(f"\nTraining — {args.epochs} epochs, {total_steps} steps")
    print(f"  effective batch = {args.batch} × {args.grad_accum} = {args.batch * args.grad_accum}")
    t0 = time.time()
    trainer.train(resume_from_checkpoint=args.resume)
    wall = time.time() - t0
    print(f"\nTraining complete in {wall/60:.1f} min")

    # ── Save adapter ──────────────────────────────────────────────────────────
    adapter_dir = output_dir / "adapter"
    model.save_pretrained(str(adapter_dir))
    tokenizer.save_pretrained(str(adapter_dir))
    print(f"Adapter saved → {adapter_dir}")

    # ── Merge ─────────────────────────────────────────────────────────────────
    merged_dir = output_dir / "merged"
    if not args.no_merge:
        print("\nMerging LoRA weights into base model …")
        # Reload in bf16 (not 4-bit) for a clean merge
        base_model = AutoModelForCausalLM.from_pretrained(
            args.model,
            torch_dtype=torch.bfloat16,
            device_map="auto" if device == "cuda" else None,
        )
        merged_model = PeftModel.from_pretrained(base_model, str(adapter_dir))
        merged_model = merged_model.merge_and_unload()
        merged_model.save_pretrained(str(merged_dir), safe_serialization=True)
        tokenizer.save_pretrained(str(merged_dir))
        print(f"Merged model saved → {merged_dir}")
        print("Next step: run export_cbt_model.py to convert to LiteRT INT4")
    else:
        print("[--no-merge] Skipping merge. Run export_cbt_model.py with --adapter to use adapter path.")

    # ── Stats ─────────────────────────────────────────────────────────────────
    stats = RunStats(
        model=args.model,
        epochs=args.epochs,
        batch_size=args.batch,
        grad_accum=args.grad_accum,
        effective_batch=args.batch * args.grad_accum,
        lr=args.lr,
        max_seq=args.max_seq,
        lora_r=args.lora_r,
        lora_alpha=args.lora_alpha,
        lora_dropout=args.lora_dropout,
        train_examples=len(train_ds),
        eval_examples=len(eval_ds),
        trainable_params=trainable,
        total_params=total,
        steps_per_epoch=steps_per_epoch,
        total_steps=total_steps,
        loss_history=loss_history,
        wall_seconds=round(wall, 1),
        merged=not args.no_merge,
        output_dir=str(output_dir),
    )
    stats_path = output_dir / "run_stats.json"
    stats_path.write_text(json.dumps(asdict(stats), indent=2), encoding="utf-8")
    print(f"Run stats → {stats_path}")

    # Quick loss summary
    if loss_history:
        final_train = next((e["train_loss"] for e in reversed(loss_history) if "train_loss" in e), None)
        final_eval  = next((e["eval_loss"]  for e in reversed(loss_history) if "eval_loss"  in e), None)
        print(f"\nFinal train loss: {final_train}   eval loss: {final_eval}")


# ─────────────────────────────────────────────────────────────────────────────
# Entry point
# ─────────────────────────────────────────────────────────────────────────────

if __name__ == "__main__" or _in_notebook:
    args = parse_args()
    train(args)
