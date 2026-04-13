"""
prepare_goEmotions_base.py
==========================
Generates the GoEmotions base layer asset for AffectiveMlp warm-start.

Loads the GoEmotions "simplified" train split (43,410 examples, already filtered
to ≥2/3 rater agreement), keeps only single-label examples (~30–40 % of train),
maps each of the 28 emotion classes to a (valence, arousal) coordinate pair from
the Russell & Barrett 1999 circumplex table, runs each text through the production
Arctic Embed XS ONNX model, then subsamples ~1 000 examples with equal quadrant
representation before writing a compact binary asset.

Usage (run from the Lattice project root):
    python scripts/prepare_goEmotions_base.py

Dependencies:
    pip install datasets onnxruntime numpy

Prerequisite:
    The embedding ONNX model must be present at:
        core-logic/src/main/assets/snowflake-arctic-embed-xs.onnx
    If absent, fetch it with: ./gradlew downloadModels

Output:
    core-logic/src/main/assets/training/goEmotions_base_v1.bin

Binary format (all values little-endian):
    [int32]  count  — number of rows
    [int32]  dim    — embedding dimension (384)
    count × (dim + 2) × float32
        [0 : dim]   embedding vector (384 floats, mean-pooled)
        [dim]       valence  (float32, range [-1, 1])
        [dim + 1]   arousal  (float32, range [-1, 1])

After running:
    git add core-logic/src/main/assets/training/goEmotions_base_v1.bin
    git commit -m "chore(training): add GoEmotions base layer asset v1"
"""

import pathlib
import random
import struct
import sys
import unicodedata

import numpy as np

# ── Paths (relative to Lattice project root) ──────────────────────────────────
VOCAB_PATH = pathlib.Path("core-logic/src/main/assets/vocab.txt")
MODEL_PATH = pathlib.Path("core-logic/src/main/assets/snowflake-arctic-embed-xs.onnx")
OUT_PATH   = pathlib.Path("core-logic/src/main/assets/training/goEmotions_base_v1.bin")

TARGET_PER_QUADRANT = 250   # ~1 000 total across four quadrants
MAX_SEQ_LEN         = 512   # must match WordPieceTokenizer.MAX_SEQ_LEN in Kotlin
EMBEDDING_DIM       = 384
RANDOM_SEED         = 42

# ── Russell & Barrett (1999) circumplex coordinates ───────────────────────────
#
# Full 28-entry lookup table for the GoEmotions "simplified" label set.
# Valence (V) in [-1, 1]: negative = unpleasant, positive = pleasant.
# Arousal (A) in [-1, 1]: negative = deactivated, positive = activated.
#
# Primary source:
#   Russell, J. A., & Barrett, L. F. (1999). Core affect, prototypical emotional
#   episodes, and other things called emotion: Dissecting the elephant.
#   Journal of Personality and Social Psychology, 76(5), 805–819.
#   https://doi.org/10.1037/0022-3514.76.5.805
#
# Supplemented by affective norms from:
#   Warriner, A. B., Kuperman, V., & Brysbaert, M. (2013). Norms of valence,
#   arousal, and dominance for 13,915 English lemmas. Behavior Research Methods,
#   45(4), 1191–1207. https://doi.org/10.3758/s13428-012-0314-x
#
#   Mohammad, S. M. (2018). Obtaining reliable human ratings of valence, arousal,
#   and dominance for 20,000 English words. Proceedings of ACL 2018.
#
# Neutral is (0.0, 0.0) per the training roadmap.
# Label IDs follow the HuggingFace go_emotions "simplified" schema (0–27).
#
#  ID  | Label           |   V     A
# -----|-----------------|----------
#   0  | admiration      |  0.72   0.25
#   1  | amusement       |  0.80   0.48
#   2  | anger           | -0.73   0.82
#   3  | annoyance       | -0.50   0.42
#   4  | approval        |  0.62   0.18
#   5  | caring          |  0.72   0.20
#   6  | confusion       | -0.18   0.32
#   7  | curiosity       |  0.30   0.52
#   8  | desire          |  0.54   0.62
#   9  | disappointment  | -0.62  -0.30
#  10  | disapproval     | -0.62   0.22
#  11  | disgust         | -0.78   0.32
#  12  | embarrassment   | -0.48   0.38
#  13  | excitement      |  0.78   0.82
#  14  | fear            | -0.70   0.72
#  15  | gratitude       |  0.82   0.28
#  16  | grief           | -0.82  -0.50
#  17  | joy             |  0.90   0.60
#  18  | love            |  0.90   0.42
#  19  | nervousness     | -0.48   0.70
#  20  | optimism        |  0.72   0.40
#  21  | pride           |  0.78   0.48
#  22  | realization     |  0.10   0.28
#  23  | relief          |  0.72  -0.28
#  24  | remorse         | -0.72  -0.20
#  25  | sadness         | -0.80  -0.40
#  26  | surprise        |  0.12   0.72
#  27  | neutral         |  0.00   0.00
EMOTION_VA: dict[int, tuple[float, float]] = {
     0: ( 0.72,  0.25),   # admiration
     1: ( 0.80,  0.48),   # amusement
     2: (-0.73,  0.82),   # anger
     3: (-0.50,  0.42),   # annoyance
     4: ( 0.62,  0.18),   # approval
     5: ( 0.72,  0.20),   # caring
     6: (-0.18,  0.32),   # confusion
     7: ( 0.30,  0.52),   # curiosity
     8: ( 0.54,  0.62),   # desire
     9: (-0.62, -0.30),   # disappointment
    10: (-0.62,  0.22),   # disapproval
    11: (-0.78,  0.32),   # disgust
    12: (-0.48,  0.38),   # embarrassment
    13: ( 0.78,  0.82),   # excitement
    14: (-0.70,  0.72),   # fear
    15: ( 0.82,  0.28),   # gratitude
    16: (-0.82, -0.50),   # grief
    17: ( 0.90,  0.60),   # joy
    18: ( 0.90,  0.42),   # love
    19: (-0.48,  0.70),   # nervousness
    20: ( 0.72,  0.40),   # optimism
    21: ( 0.78,  0.48),   # pride
    22: ( 0.10,  0.28),   # realization
    23: ( 0.72, -0.28),   # relief
    24: (-0.72, -0.20),   # remorse
    25: (-0.80, -0.40),   # sadness
    26: ( 0.12,  0.72),   # surprise
    27: ( 0.00,  0.00),   # neutral
}

LABEL_NAMES: dict[int, str] = {
     0: "admiration",    1: "amusement",      2: "anger",         3: "annoyance",
     4: "approval",      5: "caring",         6: "confusion",     7: "curiosity",
     8: "desire",        9: "disappointment", 10: "disapproval",  11: "disgust",
    12: "embarrassment", 13: "excitement",    14: "fear",         15: "gratitude",
    16: "grief",         17: "joy",           18: "love",         19: "nervousness",
    20: "optimism",      21: "pride",         22: "realization",  23: "relief",
    24: "remorse",       25: "sadness",       26: "surprise",     27: "neutral",
}


def quadrant(v: float, a: float) -> int:
    """Return 1–4 following the CBT pipeline convention (matches CircumplexMapper.kt)."""
    if v >= 0 and a >= 0:
        return 1
    if v < 0 and a >= 0:
        return 2
    if v < 0 and a < 0:
        return 3
    return 4  # v >= 0, a < 0


# ── WordPiece tokenizer ───────────────────────────────────────────────────────
# Mirrors WordPieceTokenizer.kt exactly so embeddings are consistent with the
# production EmbeddingProvider:
#   1. Lowercase
#   2. Replace control chars with space; pad CJK and punctuation with spaces
#   3. Whitespace-split into words
#   4. Greedy longest-match WordPiece on each word
#   5. Wrap with [CLS] (101) / [SEP] (102); truncate to MAX_SEQ_LEN

def _is_control(ch: str) -> bool:
    cat = unicodedata.category(ch)
    return cat in ("Cc", "Cf")


def _is_cjk(cp: int) -> bool:
    return (
        0x4E00 <= cp <= 0x9FFF or 0x3400 <= cp <= 0x4DBF or
        0x20000 <= cp <= 0x2A6DF or 0x2A700 <= cp <= 0x2B73F or
        0x2B740 <= cp <= 0x2B81F or 0x2B820 <= cp <= 0x2CEAF or
        0xF900 <= cp <= 0xFAFF or 0x2F800 <= cp <= 0x2FA1F
    )


def _is_punct(ch: str) -> bool:
    cp = ord(ch)
    if 33 <= cp <= 47 or 58 <= cp <= 64 or 91 <= cp <= 96 or 123 <= cp <= 126:
        return True
    return unicodedata.category(ch).startswith("P")


class WordPieceTokenizer:
    CLS_ID = 101
    SEP_ID = 102
    UNK_ID = 100

    def __init__(self, vocab_path: pathlib.Path) -> None:
        lines = vocab_path.read_text(encoding="utf-8").splitlines()
        self.vocab: dict[str, int] = {tok: i for i, tok in enumerate(lines)}

    def encode(self, text: str) -> tuple[list[int], list[int]]:
        tokens = self._tokenize(text)[: MAX_SEQ_LEN - 2]
        ids = (
            [self.CLS_ID]
            + [self.vocab.get(t, self.UNK_ID) for t in tokens]
            + [self.SEP_ID]
        )
        return ids, [1] * len(ids)

    def _tokenize(self, text: str) -> list[str]:
        return [wp for word in self._basic_tokenize(text) for wp in self._word_piece(word)]

    def _basic_tokenize(self, text: str) -> list[str]:
        buf: list[str] = []
        for ch in text.lower():
            cp = ord(ch)
            if _is_control(ch):
                buf.append(" ")
            elif _is_cjk(cp) or _is_punct(ch):
                buf.extend((" ", ch, " "))
            else:
                buf.append(ch)
        return "".join(buf).split()

    def _word_piece(self, word: str) -> list[str]:
        if len(word) > 200:
            return ["[UNK]"]
        pieces: list[str] = []
        start = 0
        while start < len(word):
            prefix = "" if start == 0 else "##"
            end = len(word)
            matched: str | None = None
            while end > start:
                candidate = prefix + word[start:end]
                if candidate in self.vocab:
                    matched = candidate
                    break
                end -= 1
            if matched is None:
                return ["[UNK]"]
            pieces.append(matched)
            start = end
        return pieces


# ── ONNX inference ────────────────────────────────────────────────────────────

def make_session(model_path: pathlib.Path):
    import onnxruntime as ort
    opts = ort.SessionOptions()
    opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    opts.intra_op_num_threads = 4
    return ort.InferenceSession(str(model_path), sess_options=opts)


def embed(session, tokenizer: WordPieceTokenizer, text: str) -> np.ndarray:
    """Tokenize text and run ONNX → mean-pooled 384-dim float32 vector."""
    ids, mask = tokenizer.encode(text)
    n = len(ids)
    inputs = {
        "input_ids":      np.array([ids],             dtype=np.int64),
        "attention_mask": np.array([mask],            dtype=np.int64),
        "token_type_ids": np.zeros((1, n),            dtype=np.int64),
    }
    # output[0] shape: [1, seq_len, 384] — mean-pool over seq_len (matches meanPool() in Kotlin)
    token_vecs: np.ndarray = session.run(None, inputs)[0][0]  # [seq_len, 384]
    return token_vecs.mean(axis=0).astype(np.float32)          # [384]


# ── Binary writer ─────────────────────────────────────────────────────────────

def write_bin(
    path: pathlib.Path,
    rows: list[tuple[np.ndarray, float, float]],
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    count = len(rows)
    with path.open("wb") as f:
        f.write(struct.pack("<ii", count, EMBEDDING_DIM))
        for emb, v, a in rows:
            f.write(emb.tobytes())           # 384 × float32 LE
            f.write(struct.pack("<ff", v, a))
    size_mb = path.stat().st_size / 1_048_576
    print(f"  wrote {count} rows -> {path}  ({size_mb:.2f} MB)")


# ── Main ──────────────────────────────────────────────────────────────────────

def main() -> None:
    random.seed(RANDOM_SEED)
    np.random.seed(RANDOM_SEED)

    for p in (VOCAB_PATH, MODEL_PATH):
        if not p.exists():
            print(f"ERROR: required asset not found: {p}")
            print("Run './gradlew downloadModels' to fetch the ONNX model.")
            sys.exit(1)

    print("Loading GoEmotions (simplified, train split) …")
    from datasets import load_dataset
    ds = load_dataset(
        "google-research-datasets/go_emotions",
        "simplified",
        split="train",
        trust_remote_code=False,
    )
    total_train = len(ds)

    # Keep only single-label examples to avoid ambiguous (v, a) targets.
    # The "simplified" config already applies ≥2/3 rater agreement filtering.
    single = [ex for ex in ds if len(ex["labels"]) == 1]
    print(f"  total train  : {total_train:>6,}")
    print(f"  single-label : {len(single):>6,}")

    # Attach (v, a) and quadrant
    labelled: list[dict] = []
    for ex in single:
        lid = ex["labels"][0]
        v, a = EMOTION_VA[lid]
        labelled.append({
            "text":     ex["text"],
            "label_id": lid,
            "v": v, "a": a,
            "quadrant": quadrant(v, a),
        })

    print("\nPer-quadrant inventory (before subsampling):")
    for q in range(1, 5):
        count = sum(1 for x in labelled if x["quadrant"] == q)
        label_ids = {x["label_id"] for x in labelled if x["quadrant"] == q}
        names = ", ".join(LABEL_NAMES[i] for i in sorted(label_ids))
        print(f"  Q{q}: {count:>5,}  [{names}]")

    # Balance: shuffle then cap each quadrant at TARGET_PER_QUADRANT
    random.shuffle(labelled)
    by_quadrant: dict[int, list] = {q: [] for q in range(1, 5)}
    for ex in labelled:
        q = ex["quadrant"]
        if len(by_quadrant[q]) < TARGET_PER_QUADRANT:
            by_quadrant[q].append(ex)

    selected = [ex for bucket in by_quadrant.values() for ex in bucket]
    print(f"\nSubsampled {len(selected)} examples ({TARGET_PER_QUADRANT}/quadrant cap):")
    for q in range(1, 5):
        print(f"  Q{q}: {len(by_quadrant[q])}")

    # Generate embeddings
    print(f"\nLoading ONNX model ({MODEL_PATH}) …")
    session   = make_session(MODEL_PATH)
    tokenizer = WordPieceTokenizer(VOCAB_PATH)

    rows: list[tuple[np.ndarray, float, float]] = []
    n_total = len(selected)
    for i, ex in enumerate(selected, 1):
        if i % 50 == 0 or i == n_total:
            print(f"  embedding {i:>4}/{n_total} …", end="\r", flush=True)
        emb = embed(session, tokenizer, ex["text"])
        rows.append((emb, float(ex["v"]), float(ex["a"])))
    print()

    print("\nWriting binary asset …")
    write_bin(OUT_PATH, rows)

    print(f"\ndone — {len(rows)} rows × {EMBEDDING_DIM + 2} floats each")
    print("Next step: git add the output file (see docstring for commit command).")


if __name__ == "__main__":
    main()
