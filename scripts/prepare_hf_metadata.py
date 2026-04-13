"""
prepare_hf_metadata.py
======================
Generates viewer-friendly JSONL files for the masked-kunsiquat/clinical-personas
HuggingFace dataset repo.

The seed JSON files contain deeply nested structures and base64-encoded 384-dim
embeddings that break the HuggingFace Data Viewer (TooBigContentError). This
script flattens each file into two JSONL files — one for journal entries, one for
Watson's activity hierarchy — with embeddings stripped.

Usage (run from the Lattice project root):
    python scripts/prepare_hf_metadata.py

Output (commit these to the HuggingFace dataset repo under metadata/):
    metadata/journal_entries.jsonl   — one row per entry, all three personas
    metadata/activities.jsonl        — Watson's BA activity hierarchy

After running:
    cd /path/to/clinical-personas-hf-repo
    mkdir -p metadata
    cp /path/to/Lattice/metadata/*.jsonl metadata/
    git add metadata/
    git commit -m "add viewer-friendly JSONL metadata"
    git push
"""

import json
import pathlib

SEED_DIR = pathlib.Path("core-data/src/main/assets/seeds")
OUT_DIR = pathlib.Path("scripts")

# Fields to drop from journal entries before writing.
# embeddingBase64 is a 1536-byte blob that causes TooBigContentError in the viewer.
ENTRY_DROP = {"embeddingBase64"}


def flatten_entries(persona: str, data: dict) -> list[dict]:
    """Return one flat dict per journal entry, tagged with persona name."""
    rows = []
    for entry in data.get("journalEntries", []):
        row = {k: v for k, v in entry.items() if k not in ENTRY_DROP}
        row["persona"] = persona
        # Replace mentions list with a simple count — personIds are UUIDs that
        # only resolve inside the app and add noise to the viewer.
        row["mentionCount"] = len(row.pop("mentions", []))
        rows.append(row)
    return rows


def flatten_activities(persona: str, data: dict) -> list[dict]:
    """Return one flat dict per activity, tagged with persona name."""
    return [
        {**activity, "persona": persona}
        for activity in data.get("activityHierarchy", [])
    ]


def write_jsonl(path: pathlib.Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(f"  wrote {len(rows)} rows → {path}")


def main() -> None:
    all_entries: list[dict] = []
    all_activities: list[dict] = []

    for seed_file in sorted(SEED_DIR.glob("*.json")):
        persona = seed_file.stem  # "holmes", "watson", "werther"
        print(f"processing {persona}.json …")
        data = json.loads(seed_file.read_text(encoding="utf-8"))

        entries = flatten_entries(persona, data)
        activities = flatten_activities(persona, data)

        print(f"  {len(entries)} entries, {len(activities)} activities")
        all_entries.extend(entries)
        all_activities.extend(activities)

    write_jsonl(OUT_DIR / "journal_entries.jsonl", all_entries)
    if all_activities:
        write_jsonl(OUT_DIR / "activities.jsonl", all_activities)

    print(f"\ndone — {len(all_entries)} total entries, {len(all_activities)} activities")


if __name__ == "__main__":
    main()
