"""
curate_cbt_training_data.py
===========================
Generates a supervised fine-tuning (SFT) dataset for the CBT LoRA adapter.

Each example is a (instruction, reframe) pair where:
  - instruction  = exactly what ReframingLoop.buildInterventionPrompt() would produce
  - reframe      = a Gemini-generated reframe that obeys INTERVENTION_SYSTEM constraints

Two output files are written:
  scripts/output/cbt_training_data.jsonl  — full records (inspect / filter / audit)
  scripts/output/gemma_ft_data.jsonl      — Gemma chat-template format for SFT trainer

Strategy distribution target (~550 total):
  SOCRATIC_REALITY_TESTING  150   (v<0, a≥0)
  BEHAVIORAL_ACTIVATION     150   (v<0, a<0)
  REFLECTION                150   (0 ≤ v < 0.4)
  STRENGTHS_AFFIRMATION     100   (v ≥ 0.4)

Sources:
  1. Seed personas (holmes / watson / werther) — real masked entries, already labelled
  2. Gemini-generated synthetic entries — fills coverage gaps per strategy

Classification model : gemini-2.5-flash-lite   (synthetic entry generation)
Reframe model        : gemini-2.5-flash-lite   (no thinking overhead; ~$0.05 for 550 examples)

Usage (from Lattice project root):
    pip install google-genai tqdm
    export GEMINI_API_KEY=<your_key>
    python scripts/curate_cbt_training_data.py

Optional flags:
    --dry-run          Run on seed entries only; skip synthetic generation
    --target N         Total examples to generate (default 550)
    --flash-reframe    Use gemini-2.0-flash for reframes too (faster / cheaper)
"""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import random
import re
import sys
import time
from dataclasses import dataclass, asdict
from typing import Optional

try:
    from google import genai
    from google.genai import types as genai_types
except ImportError:
    sys.exit(
        "Missing dependency: pip install google-genai\n"
        "Then set GEMINI_API_KEY and re-run."
    )

try:
    from tqdm import tqdm
except ImportError:
    def tqdm(it, **_):  # type: ignore[misc]
        return it

# ── Paths ─────────────────────────────────────────────────────────────────────
# When running as a script from the repo root, paths resolve automatically.
# When copy-pasting into Colab, set these two variables to match your environment.
_in_notebook = "__file__" not in dir()
SEEDS_DIR  = pathlib.Path("/content/seeds") if _in_notebook else pathlib.Path(__file__).parent.parent / "core-data/src/main/assets/seeds"
OUTPUT_DIR = pathlib.Path("/content/output") if _in_notebook else pathlib.Path(__file__).parent.parent / "scripts/output"

# ── Models ────────────────────────────────────────────────────────────────────
CLASSIFY_MODEL = "gemini-2.5-flash-lite"   # synthetic entry generation
REFRAME_MODEL  = "gemini-2.0-flash"        # faster; right for short constrained reframe generation

# ── Strategy routing — mirrors ReframingLoop.selectStrategy() ─────────────────
AFFIRMATION_THRESHOLD = 0.4  # matches ReframingLoop.AFFIRMATION_THRESHOLD

STRATEGY_SOCRATIC    = "SOCRATIC_REALITY_TESTING"
STRATEGY_BA          = "BEHAVIORAL_ACTIVATION"
STRATEGY_REFLECTION  = "REFLECTION"
STRATEGY_AFFIRMATION = "STRENGTHS_AFFIRMATION"

STRATEGY_TARGETS = {
    STRATEGY_SOCRATIC:    150,
    STRATEGY_BA:          150,
    STRATEGY_REFLECTION:  150,
    STRATEGY_AFFIRMATION: 100,
}

# ── Prompts — copied verbatim from ReframingLoop companion object ─────────────
INTERVENTION_SYSTEM = (
    "You are a CBT journaling assistant. "
    "Write exactly 2-3 sentences as a brief, grounded, first-person reframe. "
    "Interpret the entry literally — do not contradict what it says or invent details not in the text. "
    "Never repeat or amplify the negative thought. "
    "No motivational cheerleading. "
    "Write in first person singular only (I, me, my). Never use 'we', 'let\u2019s', or 'you'. "
    "No markdown, no asterisks, no ellipses, no therapist language."
)

TECHNIQUE_BY_STRATEGY = {
    STRATEGY_SOCRATIC: (
        "Question whether the fear or assumption is definitely true, "
        "then land on a more balanced reading."
    ),
    STRATEGY_BA: (
        "Name the low-energy or avoidance pattern as temporary, not a fixed trait. "
        "End with one specific, minimal action that addresses what the entry actually describes."
    ),
    STRATEGY_REFLECTION: (
        "Notice what this entry reveals about what matters to you — "
        "name the value or relationship it points to."
    ),
    STRATEGY_AFFIRMATION: (
        "Name the strength or effort this entry shows and connect it to what matters to you."
    ),
}

# Gemma chat-template tokens
GEMMA_USER_START  = "<start_of_turn>user"
GEMMA_USER_END    = "<end_of_turn>"
GEMMA_MODEL_START = "<start_of_turn>model"
GEMMA_MODEL_END   = "<end_of_turn>"


# ── Data classes ──────────────────────────────────────────────────────────────
@dataclass
class TrainingExample:
    entry: str            # display text (may contain @name / !place tokens or [PERSON_uuid])
    strategy: str
    distortions: list[str]
    valence: float
    arousal: float
    instruction: str      # exactly what buildInterventionPrompt() would produce
    reframe: str          # Gemini-generated training target
    source: str           # "seed_holmes" | "seed_watson" | "seed_werther" | "synthetic"


# ── Strategy helpers ──────────────────────────────────────────────────────────
def select_strategy(valence: float, arousal: float) -> str:
    if valence < 0 and arousal >= 0:
        return STRATEGY_SOCRATIC
    if valence < 0 and arousal < 0:
        return STRATEGY_BA
    if valence < AFFIRMATION_THRESHOLD:
        return STRATEGY_REFLECTION
    return STRATEGY_AFFIRMATION


def build_instruction(entry: str, strategy: str, distortions: list[str]) -> str:
    """Mirrors ReframingLoop.buildInterventionPrompt() (no BA activity, no evidence)."""
    distortion_line = ""
    if distortions:
        distortion_line = f"Distortions present: {', '.join(distortions)}\n"
    technique = TECHNIQUE_BY_STRATEGY[strategy]
    return (
        f'Journal entry: "{entry}"\n\n'
        f"{distortion_line}"
        f"{technique}\n\n"
        "Output only the reframe."
    )


# ── Seed loader ───────────────────────────────────────────────────────────────
def load_seed_entries() -> list[dict]:
    """
    Parses holmes/watson/werther.json and returns a flat list of entry dicts with keys:
        content, valence, arousal, cognitiveDistortions, source
    Skips entries with no content or no mood labels.
    """
    entries = []
    for persona_file in SEEDS_DIR.glob("*.json"):
        persona = persona_file.stem  # "holmes" | "watson" | "werther"
        data = json.loads(persona_file.read_text(encoding="utf-8"))
        for e in data.get("journalEntries", []):
            content = e.get("content", "")
            valence = e.get("valence")
            arousal = e.get("arousal")
            if not content or valence is None or arousal is None:
                continue
            entries.append(
                {
                    "content": content,
                    "valence": float(valence),
                    "arousal": float(arousal),
                    "cognitiveDistortions": e.get("cognitiveDistortions", []),
                    "source": f"seed_{persona}",
                }
            )
    return entries


# ── Gemini client ─────────────────────────────────────────────────────────────
def make_client() -> genai.Client:
    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        try:
            from google.colab import userdata
            api_key = userdata.get("GEMINI_API_KEY")
        except Exception:
            pass
    if not api_key:
        sys.exit("Set GEMINI_API_KEY in Colab secrets or as an environment variable.")
    return genai.Client(api_key=api_key)


def gemini_json(
    client: genai.Client,
    model: str,
    prompt: str,
    schema: dict,
    retries: int = 3,
) -> dict | None:
    """Calls Gemini with JSON response mode. Returns parsed dict or None on failure."""
    for attempt in range(retries):
        try:
            response = client.models.generate_content(
                model=model,
                contents=prompt,
                config=genai_types.GenerateContentConfig(
                    response_mime_type="application/json",
                    response_schema=schema,
                    temperature=0.3,
                ),
            )
            return json.loads(response.text)
        except Exception as exc:  # noqa: BLE001
            if attempt < retries - 1:
                time.sleep(2 ** attempt)
            else:
                print(f"[warn] gemini_json failed after {retries} attempts: {exc}", file=sys.stderr)
                return None


def gemini_text(
    client: genai.Client,
    model: str,
    system: str,
    user: str,
    retries: int = 3,
) -> str | None:
    """Calls Gemini for a free-text completion with a system instruction."""
    for attempt in range(retries):
        try:
            response = client.models.generate_content(
                model=model,
                contents=user,
                config=genai_types.GenerateContentConfig(
                    system_instruction=system,
                    temperature=0.7,
                    max_output_tokens=256,
                ),
            )
            # Check finish reason — SAFETY stops generation mid-sentence; skip rather than retry.
            candidate = response.candidates[0] if response.candidates else None
            if candidate and str(candidate.finish_reason) not in ("FinishReason.STOP", "STOP", "1"):
                print(f"[warn] generation stopped early: finish_reason={candidate.finish_reason}", file=sys.stderr)
                return None
            text = response.text.strip() if response.text else None
            if text:
                return text.replace("…", "...")
        except Exception as exc:  # noqa: BLE001
            if attempt < retries - 1:
                time.sleep(2 ** attempt)
            else:
                print(f"[warn] gemini_text failed after {retries} attempts: {exc}", file=sys.stderr)
                return None
    return None


# ── Validation ────────────────────────────────────────────────────────────────
_SENTENCE_END = re.compile(r"[.!?]+")
_FORBIDDEN    = re.compile(r"\b(we|let's|you\b|your\b)", re.IGNORECASE)
_FIRST_PERSON = re.compile(r"\b(I|me|my|mine|myself)\b")


def validate_reframe(text: str) -> tuple[bool, str]:
    """
    Returns (ok, reason). Checks:
    - Minimum length (safety-cut responses are very short)
    - First-person singular present
    - No forbidden pronouns
    - No markdown artefacts
    Note: sentence count not checked — safety filtering can cut responses mid-sentence,
    and retrying the same content won't help.
    """
    if len(text) < 40:
        return False, f"response too short ({len(text)} chars) — likely safety-cut"
    if not _FIRST_PERSON.search(text):
        return False, "no first-person singular pronoun"
    if _FORBIDDEN.search(text):
        return False, "contains forbidden pronoun (we/let's/you)"
    if any(c in text for c in ("*", "#")):
        return False, "markdown artefact detected"
    return True, ""


# ── Synthetic entry generation ────────────────────────────────────────────────
_SYNTHETIC_SYSTEM = (
    "You are a realistic journal entry generator for a CBT app. "
    "Write a single brief journal entry (1-3 sentences) in first-person present or past tense. "
    "Use natural, informal language. No hashtags. No special formatting. "
    "Replace any person names with @Name placeholders (e.g. @Alex, @Mum, @Sam). "
    "Replace any place names with !Place placeholders (e.g. !The Office, !Home). "
    "Output ONLY the journal entry text."
)

_SYNTHETIC_PROMPTS = {
    STRATEGY_SOCRATIC: (
        "Write a journal entry where the writer is anxious or angry about something that "
        "might not be as bad as they think. The entry should reflect distorted thinking "
        "like catastrophizing, mind reading, or fortune telling."
    ),
    STRATEGY_BA: (
        "Write a journal entry where the writer feels low, tired, or withdrawn. "
        "They might be avoiding something or feeling stuck. "
        "Reflects a depressed or fatigued mood."
    ),
    STRATEGY_REFLECTION: (
        "Write a journal entry about an ordinary positive or neutral moment — "
        "plans, a small pleasant event, or spending time with someone. "
        "Tone is mildly positive but not excited. No strong emotion."
    ),
    STRATEGY_AFFIRMATION: (
        "Write a journal entry where the writer describes something they did well, "
        "handled with care, or felt genuinely good about. Clearly positive mood."
    ),
}

# Representative valence/arousal ranges per strategy (used to assign coords to synthetic entries)
_SYNTHETIC_VA = {
    STRATEGY_SOCRATIC:    lambda: (round(random.uniform(-0.7, -0.1), 2), round(random.uniform(0.1, 0.9), 2)),
    STRATEGY_BA:          lambda: (round(random.uniform(-0.8, -0.1), 2), round(random.uniform(-0.9, -0.1), 2)),
    STRATEGY_REFLECTION:  lambda: (round(random.uniform(0.05, 0.35), 2), round(random.uniform(-0.5, 0.5), 2)),
    STRATEGY_AFFIRMATION: lambda: (round(random.uniform(0.4, 0.9), 2), round(random.uniform(-0.3, 0.8), 2)),
}

# Distortions that commonly appear per strategy (used to sample realistic labels for synthetics)
_TYPICAL_DISTORTIONS = {
    STRATEGY_SOCRATIC:    ["Catastrophizing", "Mind Reading", "Fortune-telling", "Overgeneralization", "All-or-nothing thinking"],
    STRATEGY_BA:          ["Emotional Reasoning", "Labeling", "Should statements", "Mental filter", "All-or-nothing thinking"],
    STRATEGY_REFLECTION:  ["Disqualifying the positive", "Emotional Reasoning", "Mental filter", "Should statements"],
    STRATEGY_AFFIRMATION: [],
}


def generate_synthetic_entry(client: genai.Client, strategy: str) -> dict | None:
    """Asks Gemini Flash to write a realistic journal entry for the given strategy."""
    text = gemini_text(
        client,
        CLASSIFY_MODEL,
        _SYNTHETIC_SYSTEM,
        _SYNTHETIC_PROMPTS[strategy],
    )
    if not text or len(text) < 10:
        return None

    v, a = _SYNTHETIC_VA[strategy]()
    typical = _TYPICAL_DISTORTIONS[strategy]
    # Randomly assign 0-2 distortions for negative quadrants, 0 for positive
    if typical:
        k = random.choices([0, 1, 2], weights=[0.3, 0.5, 0.2])[0]
        distortions = random.sample(typical, min(k, len(typical)))
    else:
        distortions = []

    return {
        "content": text,
        "valence": v,
        "arousal": a,
        "cognitiveDistortions": distortions,
        "source": "synthetic",
    }


# ── Reframe generation ────────────────────────────────────────────────────────
def generate_reframe(
    client: genai.Client,
    instruction: str,
    reframe_model: str,
    max_attempts: int = 3,
) -> str | None:
    """
    Generates a reframe using INTERVENTION_SYSTEM as the system prompt and the
    pre-built instruction string as the user message. Validates and retries up to
    max_attempts times, relaxing validation on the final attempt.
    """
    for attempt in range(max_attempts):
        text = gemini_text(client, reframe_model, INTERVENTION_SYSTEM, instruction)
        if not text:
            continue
        ok, reason = validate_reframe(text)
        if ok:
            return text
        if attempt < max_attempts - 1:
            print(f"  [retry] validation failed ({reason}); text={repr(text[:120])}", file=sys.stderr)
    # All attempts failed validation — do not return an invalid SFT target.
    return None


# ── Main ──────────────────────────────────────────────────────────────────────
def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true", help="Seed entries only; skip synthetic generation")
    parser.add_argument("--target", type=int, default=550, help="Total examples target (default 550)")
    parser.add_argument("--flash-reframe", action="store_true", help="Use gemini-2.0-flash for reframes (faster)")
    args, _ = parser.parse_known_args()  # parse_known_args ignores Colab/Jupyter kernel flags

    reframe_model = REFRAME_MODEL if args.flash_reframe else CLASSIFY_MODEL
    print(f"Classification model : {CLASSIFY_MODEL}")
    print(f"Reframe model        : {reframe_model}")

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    client = make_client()

    # ── 1. Load seed entries ──────────────────────────────────────────────────
    print("\n[1/3] Loading seed entries…")
    raw_seeds = load_seed_entries()
    print(f"      {len(raw_seeds)} seed entries loaded from {SEEDS_DIR}")

    # ── 2. Convert seeds to TrainingExamples ──────────────────────────────────
    print("\n[2/3] Generating reframes for seed entries…")
    examples: list[TrainingExample] = []
    counts: dict[str, int] = {s: 0 for s in STRATEGY_TARGETS}

    for raw in tqdm(raw_seeds, desc="seeds"):
        entry   = raw["content"]
        valence = raw["valence"]
        arousal = raw["arousal"]
        dists   = raw["cognitiveDistortions"]
        source  = raw["source"]

        strategy = select_strategy(valence, arousal)
        # Skip REFLECTION_CARD path (no distortions + REFLECTION) — no LLM reframe exists
        if strategy == STRATEGY_REFLECTION and not dists:
            continue

        instruction = build_instruction(entry, strategy, dists)
        reframe = generate_reframe(client, instruction, reframe_model)
        if not reframe:
            print(f"  [skip] reframe generation failed for seed entry", file=sys.stderr)
            continue

        examples.append(TrainingExample(
            entry=entry,
            strategy=strategy,
            distortions=dists,
            valence=valence,
            arousal=arousal,
            instruction=instruction,
            reframe=reframe,
            source=source,
        ))
        counts[strategy] = counts.get(strategy, 0) + 1

    print(f"      Seed examples after reframe generation: {len(examples)}")
    for s, c in counts.items():
        print(f"        {s}: {c}")

    # ── 3. Fill gaps with synthetic entries ───────────────────────────────────
    if not args.dry_run:
        print("\n[3/3] Generating synthetic entries to fill coverage gaps…")
        scale = args.target / sum(STRATEGY_TARGETS.values())
        scaled_targets = {s: max(0, int(t * scale) - counts.get(s, 0)) for s, t in STRATEGY_TARGETS.items()}

        for strategy, needed in scaled_targets.items():
            if needed <= 0:
                print(f"  {strategy}: already at target, skipping")
                continue
            print(f"  {strategy}: generating {needed} synthetic entries…")
            generated = 0
            failures  = 0
            with tqdm(total=needed, desc=strategy[:12]) as pbar:
                while generated < needed and failures < needed * 2:
                    raw = generate_synthetic_entry(client, strategy)
                    if not raw:
                        failures += 1
                        continue

                    dists = raw["cognitiveDistortions"]
                    # Skip REFLECTION_CARD path — production skips the LLM for
                    # zero-distortion REFLECTION entries, so don't train on them.
                    if strategy == STRATEGY_REFLECTION and not dists:
                        continue

                    instruction = build_instruction(raw["content"], strategy, dists)
                    reframe = generate_reframe(client, instruction, reframe_model)
                    if not reframe:
                        failures += 1
                        continue

                    examples.append(TrainingExample(
                        entry=raw["content"],
                        strategy=strategy,
                        distortions=dists,
                        valence=raw["valence"],
                        arousal=raw["arousal"],
                        instruction=instruction,
                        reframe=reframe,
                        source="synthetic",
                    ))
                    generated += 1
                    counts[strategy] = counts.get(strategy, 0) + 1
                    pbar.update(1)

            if failures >= needed * 2:
                print(f"  [warn] high failure rate for {strategy} ({failures} failures)", file=sys.stderr)

    # ── 4. Shuffle and write outputs ──────────────────────────────────────────
    random.shuffle(examples)

    full_path  = OUTPUT_DIR / "cbt_training_data.jsonl"
    gemma_path = OUTPUT_DIR / "gemma_ft_data.jsonl"

    with full_path.open("w", encoding="utf-8") as f_full, \
         gemma_path.open("w", encoding="utf-8") as f_gemma:

        for ex in examples:
            # Full record
            f_full.write(json.dumps(asdict(ex), ensure_ascii=False) + "\n")

            # Gemma chat-template format
            # System instruction prepended to user turn (Gemma has no separate system role)
            user_content = f"{INTERVENTION_SYSTEM}\n\n{ex.instruction}"
            gemma_record = {
                "text": (
                    f"{GEMMA_USER_START}\n{user_content}{GEMMA_USER_END}\n"
                    f"{GEMMA_MODEL_START}\n{ex.reframe}{GEMMA_MODEL_END}"
                )
            }
            f_gemma.write(json.dumps(gemma_record, ensure_ascii=False) + "\n")

    print(f"\n✓ {len(examples)} examples written")
    print(f"  Full records  → {full_path}")
    print(f"  Gemma SFT     → {gemma_path}")
    print("\nStrategy breakdown:")
    for s, c in counts.items():
        target = STRATEGY_TARGETS.get(s, "?")
        print(f"  {s:<35} {c:>4}  (target {target})")

    print("\nNext step: inspect cbt_training_data.jsonl, then run finetune_cbt_lora.py")


if __name__ == "__main__":
    main()
