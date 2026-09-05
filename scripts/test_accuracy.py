import copy
import unittest
import tempfile
import json
from pathlib import Path
import accuracy


class AccuracyTests(unittest.TestCase):
    def setUp(self):
        self.expected = {"file": "fictional.pdf", "fields": {f: "x" for f in accuracy.FIELDS},
                         "lineItems": [{"description": "Setup", "quantity": "1", "unitPrice": "USD 10"}],
                         "reviewStatus": "COMPLETE"}
        self.actual = {"fields": {f: {"value": "x", "confidence": .9, "status": "ACCEPTED"} for f in accuracy.FIELDS},
                       "lineItems": [{k: {"value": v, "confidence": .9, "status": "ACCEPTED"}
                                      for k, v in self.expected["lineItems"][0].items()}], "reviewStatus": "COMPLETE"}

    def test_failed_documents_remain_in_denominator(self):
        self.expected["fields"]["dependencies"] = None
        report = accuracy.aggregate([accuracy.evaluate(self.expected, None)])
        self.assertEqual(report["fieldAccuracyPercent"], 0)
        self.assertEqual(report["documentExactMatchPercent"], 0)

    def test_extra_duplicate_rows_are_penalized(self):
        self.actual["lineItems"].append(copy.deepcopy(self.actual["lineItems"][0]))
        result = accuracy.evaluate(self.expected, self.actual)
        self.assertFalse(result["documentExtractionExactMatch"])
        self.assertEqual(len(result["comparisons"]), 14)
        self.assertLess(result["fieldAccuracyPercent"], 100)

    def test_missing_field_is_not_correct_absence(self):
        self.expected["fields"]["dependencies"] = None
        del self.actual["fields"]["dependencies"]
        self.assertFalse(accuracy.evaluate(self.expected, self.actual)["documentExtractionExactMatch"])

    def test_routes_are_scored_separately(self):
        self.actual["reviewStatus"] = "MANUAL_REVIEW"
        result = accuracy.evaluate(self.expected, self.actual)
        self.assertTrue(result["documentExtractionExactMatch"])
        self.assertFalse(result["routingCorrect"])
        self.assertFalse(result["endToEndExactMatch"])

    def test_low_confidence_does_not_count_as_accepted(self):
        self.actual["fields"]["budget"]["status"] = "LOW_CONFIDENCE"
        result = accuracy.aggregate([accuracy.evaluate(self.expected, self.actual)])
        self.assertEqual(result["fieldAccuracyPercent"], 100)
        self.assertLess(result["acceptedPresentFieldCoveragePercent"], 100)

    def test_empty_denominators_are_not_100_percent(self):
        self.assertIsNone(accuracy.aggregate([])["fieldAccuracyPercent"])

    def test_empty_dataset_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            key = root / "key.json"
            key.write_text(json.dumps({"documentType": "INTAKE_FORM", "documents": []}))
            with self.assertRaisesRegex(ValueError, "No labelled documents"):
                accuracy.load_dataset(root, key)

    def test_missing_key_entries_cannot_silently_shrink_the_dataset(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "fictional.pdf").write_bytes(b"first")
            (root / "unlabelled.pdf").write_bytes(b"second")
            key = root / "key.json"
            key.write_text(json.dumps({"documentType": "INTAKE_FORM", "documents": [self.expected]}))
            with self.assertRaisesRegex(ValueError, "no ground truth"):
                accuracy.load_dataset(root, key)

    def test_duplicate_bytes_cannot_inflate_sample_size(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("fictional.pdf", "copy.pdf"):
                (root / name).write_bytes(b"same document")
            duplicate = copy.deepcopy(self.expected)
            duplicate["file"] = "copy.pdf"
            key = root / "key.json"
            key.write_text(json.dumps({"documentType": "INTAKE_FORM", "documents": [self.expected, duplicate]}))
            with self.assertRaisesRegex(ValueError, "Repeated document bytes"):
                accuracy.load_dataset(root, key)

    def test_ground_truth_paths_cannot_escape_sample_folder(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.expected["file"] = "../outside.pdf"
            key = root / "key.json"
            key.write_text(json.dumps({"documentType": "INTAKE_FORM", "documents": [self.expected]}))
            with self.assertRaisesRegex(ValueError, "inside the sample folder"):
                accuracy.load_dataset(root, key)


if __name__ == "__main__":
    unittest.main()
