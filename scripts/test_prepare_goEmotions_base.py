"""
test_prepare_goEmotions_base.py
================================
Unit tests for prepare_goEmotions_base.py.

No HuggingFace, ONNX, or network access required — all tests run on the
pure-Python helpers exported from the script.

Run from the Lattice project root:
    python -m pytest scripts/test_prepare_goEmotions_base.py -v
or:
    python -m unittest scripts.test_prepare_goEmotions_base
"""

import pathlib
import struct
import sys
import tempfile
import unittest

import numpy as np

# ── Import helpers from sibling script ───────────────────────────────────────
sys.path.insert(0, str(pathlib.Path(__file__).parent))
from prepare_goEmotions_base import (  # noqa: E402
    EMOTION_VA,
    EMBEDDING_DIM,
    quadrant,
    write_bin,
)


# ── TestQuadrantBoundaries ────────────────────────────────────────────────────

class TestQuadrantBoundaries(unittest.TestCase):
    """Tests for the quadrant(v, a) helper."""

    def test_four_quadrants_interior(self):
        self.assertEqual(quadrant( 0.5,  0.5), 1)
        self.assertEqual(quadrant(-0.5,  0.5), 2)
        self.assertEqual(quadrant(-0.5, -0.5), 3)
        self.assertEqual(quadrant( 0.5, -0.5), 4)

    def test_origin_is_q1(self):
        # v >= 0 and a >= 0 both satisfied at (0, 0) → Q1
        self.assertEqual(quadrant(0.0, 0.0), 1)

    def test_axis_boundaries(self):
        # Positive v, zero a → Q1 (a >= 0)
        self.assertEqual(quadrant(0.5,  0.0), 1)
        # Zero v, positive a → Q1 (v >= 0 and a >= 0)
        self.assertEqual(quadrant(0.0,  0.1), 1)
        # Zero v, negative a → Q4 (v >= 0, a < 0)
        self.assertEqual(quadrant(0.0, -0.1), 4)
        # Negative v, zero a → Q2 (v < 0, a >= 0)
        self.assertEqual(quadrant(-0.1, 0.0), 2)

    def test_all_28_emotions_return_valid_quadrant(self):
        valid = {1, 2, 3, 4}
        for eid, (v, a) in EMOTION_VA.items():
            q = quadrant(v, a)
            self.assertIn(q, valid, msg=f"emotion {eid} ({v}, {a}) returned quadrant {q}")

    def test_known_emotion_quadrants(self):
        # joy (17): (0.90, 0.60) → Q1
        v, a = EMOTION_VA[17]
        self.assertEqual(quadrant(v, a), 1, "joy must map to Q1")
        # grief (16): (-0.82, -0.50) → Q3
        v, a = EMOTION_VA[16]
        self.assertEqual(quadrant(v, a), 3, "grief must map to Q3")
        # anger (2): (-0.73, 0.82) → Q2
        v, a = EMOTION_VA[2]
        self.assertEqual(quadrant(v, a), 2, "anger must map to Q2")
        # relief (23): (0.72, -0.28) → Q4
        v, a = EMOTION_VA[23]
        self.assertEqual(quadrant(v, a), 4, "relief must map to Q4")
        # neutral (27): (0.00, 0.00) → Q1 (origin convention)
        v, a = EMOTION_VA[27]
        self.assertEqual(quadrant(v, a), 1, "neutral must map to Q1 (origin)")


# ── TestBinaryByteOrder ───────────────────────────────────────────────────────

class TestBinaryByteOrder(unittest.TestCase):
    """Tests for write_bin(): verifies little-endian float32 layout on disk."""

    def setUp(self):
        self._tmpdir = tempfile.TemporaryDirectory()
        self.out_path = pathlib.Path(self._tmpdir.name) / "test_output.bin"

    def tearDown(self):
        self._tmpdir.cleanup()

    def test_header_values(self):
        emb = np.zeros(EMBEDDING_DIM, dtype=np.float32)
        write_bin(self.out_path, [(emb, 0.0, 0.0)])
        raw = self.out_path.read_bytes()
        count, dim = struct.unpack_from("<ii", raw, 0)
        self.assertEqual(count, 1)
        self.assertEqual(dim, EMBEDDING_DIM)

    def test_first_embedding_round_trips(self):
        emb0 = np.arange(EMBEDDING_DIM, dtype=np.float32) * 0.001
        write_bin(self.out_path, [(emb0, 0.5, -0.3)])
        raw = self.out_path.read_bytes()
        offset = 8  # skip header (2 × int32)
        recovered = np.frombuffer(raw[offset: offset + EMBEDDING_DIM * 4], dtype="<f4")
        np.testing.assert_allclose(recovered, emb0, rtol=0, atol=0,
                                   err_msg="first embedding must survive write_bin round-trip exactly")

    def test_first_labels_round_trip(self):
        emb0 = np.zeros(EMBEDDING_DIM, dtype=np.float32)
        write_bin(self.out_path, [(emb0, 0.5, -0.3)])
        raw = self.out_path.read_bytes()
        label_offset = 8 + EMBEDDING_DIM * 4
        v, a = struct.unpack_from("<ff", raw, label_offset)
        self.assertAlmostEqual(v,  0.5, places=5, msg="valence must round-trip")
        self.assertAlmostEqual(a, -0.3, places=5, msg="arousal must round-trip")

    def test_two_rows_layout(self):
        emb0 = np.arange(EMBEDDING_DIM, dtype=np.float32) * 0.001
        emb1 = np.ones(EMBEDDING_DIM, dtype=np.float32) * 0.5
        write_bin(self.out_path, [(emb0, 0.5, -0.3), (emb1, -0.1, 0.2)])
        raw = self.out_path.read_bytes()

        count, dim = struct.unpack_from("<ii", raw, 0)
        self.assertEqual(count, 2)
        self.assertEqual(dim, EMBEDDING_DIM)

        row_bytes = (EMBEDDING_DIM + 2) * 4  # 384 floats + 2 label floats
        expected_size = 8 + 2 * row_bytes
        self.assertEqual(len(raw), expected_size)

        # Row 0
        offset0 = 8
        rec_emb0 = np.frombuffer(raw[offset0: offset0 + EMBEDDING_DIM * 4], dtype="<f4")
        np.testing.assert_allclose(rec_emb0, emb0, rtol=0, atol=0)
        v0, a0 = struct.unpack_from("<ff", raw, offset0 + EMBEDDING_DIM * 4)
        self.assertAlmostEqual(v0,  0.5, places=5)
        self.assertAlmostEqual(a0, -0.3, places=5)

        # Row 1
        offset1 = 8 + row_bytes
        rec_emb1 = np.frombuffer(raw[offset1: offset1 + EMBEDDING_DIM * 4], dtype="<f4")
        np.testing.assert_allclose(rec_emb1, emb1, rtol=0, atol=0)
        v1, a1 = struct.unpack_from("<ff", raw, offset1 + EMBEDDING_DIM * 4)
        self.assertAlmostEqual(v1, -0.1, places=5)
        self.assertAlmostEqual(a1,  0.2, places=5)


# ── TestSingleLabelFilter ─────────────────────────────────────────────────────

class TestSingleLabelFilter(unittest.TestCase):
    """Tests the single-label filter expression used in main()."""

    def _apply_filter(self, ds):
        return [ex for ex in ds if len(ex["labels"]) == 1]

    def test_keeps_only_single_label_examples(self):
        ds = [
            {"labels": []},       # 0 labels — excluded
            {"labels": [5]},      # 1 label  — kept
            {"labels": [1, 2]},   # 2 labels — excluded
            {"labels": [17]},     # 1 label  — kept
            {"labels": [0, 3, 5]},# 3 labels — excluded
        ]
        single = self._apply_filter(ds)
        self.assertEqual(len(single), 2)
        self.assertEqual(single[0]["labels"], [5])
        self.assertEqual(single[1]["labels"], [17])

    def test_empty_dataset_returns_empty(self):
        self.assertEqual(self._apply_filter([]), [])

    def test_all_multi_label_returns_empty(self):
        ds = [{"labels": [0, 1]}, {"labels": [2, 3, 4]}]
        self.assertEqual(self._apply_filter(ds), [])

    def test_all_single_label_kept(self):
        ds = [{"labels": [i]} for i in range(10)]
        single = self._apply_filter(ds)
        self.assertEqual(len(single), 10)

    def test_empty_labels_list_excluded(self):
        # len([]) == 0 != 1 — must not be included
        ds = [{"labels": []}, {"labels": [3]}]
        single = self._apply_filter(ds)
        self.assertEqual(len(single), 1)
        self.assertEqual(single[0]["labels"], [3])


if __name__ == "__main__":
    unittest.main()
