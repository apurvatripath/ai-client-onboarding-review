"""Local OCR only. Model files ship with pinned RapidOCR; no document uploads."""
import json
import sys
from pathlib import Path

runtime = Path(__file__).resolve().parents[1] / ".runtime" / "python"
sys.path.insert(0, str(runtime))
from rapidocr_onnxruntime import RapidOCR
import cv2
import numpy as np


def extract(path):
    engine = RapidOCR(intra_op_num_threads=2, inter_op_num_threads=2,
                      det_limit_side_len=1600, det_limit_type="max", max_side_len=2400)
    result, _ = engine(str(path))
    if not result:
        return {"text": "", "confidence": 0}
    # Keep spatial rows and columns: a wide gap marks a table cell boundary.
    words = sorted(result, key=lambda item: (min(p[1] for p in item[0]), min(p[0] for p in item[0])))
    rows = []
    for box, text, confidence in words:
        x, y = min(p[0] for p in box), sum(p[1] for p in box) / 4
        height = max(p[1] for p in box) - min(p[1] for p in box)
        row = next((r for r in reversed(rows) if abs(r[0] - y) <= max(5, height * .55)), None)
        if row is None:
            row = [y, []]
            rows.append(row)
        row[1].append((x, text, float(confidence), box))
    lines, anchors, recovered_scores = [], None, []
    pixels = cv2.imread(str(path))
    for _, words_in_row in sorted(rows):
        words_in_row.sort(key=lambda word: word[0])
        if [w[1].strip().casefold() for w in words_in_row] == ["description", "quantity", "unit price"]:
            anchors = [w[0] for w in words_in_row]
        if anchors and len(words_in_row) >= 2:
            slots = [[] for _ in anchors]
            for word in words_in_row:
                index = min(range(3), key=lambda i: abs(word[0] - anchors[i]))
                slots[index].append(word[1])
            # A detector can miss a narrow single-digit cell. Recognize the actual
            # missing cell crop; never fill it by guessing from neighboring rows.
            for index, slot in enumerate(slots):
                if slot:
                    continue
                top = max(0, int(min(p[1] for w in words_in_row for p in w[3])) - 8)
                bottom = min(pixels.shape[0], int(max(p[1] for w in words_in_row for p in w[3])) + 8)
                left = max(0, int(anchors[index]) - 10)
                right = int(anchors[index + 1]) - 10 if index < 2 else pixels.shape[1]
                crop = pixels[top:bottom, left:right]
                if not crop.size:
                    continue
                dark_y, dark_x = np.where(cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY) < 160)
                if len(dark_x) < 8:
                    continue
                crop = crop[max(0, dark_y.min()-4):dark_y.max()+5, max(0, dark_x.min()-4):dark_x.max()+5]
                recovered, _ = engine(crop, use_det=False, use_cls=False, use_rec=True)
                if recovered and float(recovered[0][1]) >= .85:
                    slot.append(recovered[0][0])
                    recovered_scores.append(float(recovered[0][1]))
            lines.append(" | ".join(" ".join(slot) for slot in slots))
        else:
            lines.append(" | ".join(w[1] for w in words_in_row))
    # Conservative page floor rather than hiding weak cells in an average.
    return {"text": "\n".join(lines), "confidence": min([float(item[2]) for item in result] + recovered_scores)}


if __name__ == "__main__":
    print(json.dumps(extract(Path(sys.argv[1])), ensure_ascii=False))
