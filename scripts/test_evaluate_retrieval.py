import importlib.util
import json
import unittest
import sys
import tempfile
from pathlib import Path

spec = importlib.util.spec_from_file_location("evaluation", Path(__file__).with_name("evaluate-retrieval.py"))
sys.dont_write_bytecode = True
evaluation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evaluation)


class EvaluationTest(unittest.TestCase):
    def test_frozen_labels_resolve_to_real_chunk_and_document_ids(self):
        corpus = json.loads((evaluation.DATA / "corpus.json").read_text(encoding="utf-8"))
        questions = json.loads((evaluation.DATA / "questions.json").read_text(encoding="utf-8"))
        evaluation.validate_fixture(corpus, questions)
        self.assertEqual(60, len(questions["questions"]))

    def test_ranking_uses_candidate_recall_and_final_precision_without_duplicate_credit(self):
        q = {"relevance": [{"chunkId": "a", "grade": 1}, {"chunkId": "b", "grade": 1}]}
        recall, ndcg, mrr = evaluation.ranking(q, {"candidateIds": ["a", "a", "b"], "selectedIds": ["x", "a", "a"]})
        self.assertEqual(1.0, recall)
        self.assertAlmostEqual((1 / evaluation.math.log2(3)) / (1 + 1 / evaluation.math.log2(3)), ndcg)
        self.assertEqual(0.5, mrr)
        self.assertIsNone(evaluation.ranking({"relevance": []}, {"candidateIds": [], "selectedIds": []}))

    def test_recall_cutoff_and_nearest_rank_p95(self):
        q = {"relevance": [{"chunkId": "answer", "grade": 1}]}
        self.assertEqual(0, evaluation.ranking(q, {"candidateIds": [str(i) for i in range(20)] + ["answer"], "selectedIds": []})[0])
        self.assertEqual(95, evaluation.p95(list(range(1, 101))))

    def test_missing_answer_reviews_stay_unknown(self):
        q = {"id": "q", "relevance": [], "answerable": False, "hard": True}
        row = {"experiment": "FAST", "candidateIds": [], "selectedIds": [], "latencyMillis": 10,
               "expansionModelCalls": 0, "embeddingCalls": 1, "answerModelCalls": 0, "rerankerCalls": 0, "fallback": False}
        result = evaluation.summarize([q], [row], {})
        self.assertIsNone(result["groundedness"])
        self.assertIsNone(result["citationCorrectness"])
        self.assertIsNone(result["recall20"])
        self.assertEqual(0, result["reviewedAnswers"])

    def test_complete_paired_runs_emit_unapproved_artifacts_and_partial_runs_are_rejected(self):
        fixture = json.loads((evaluation.DATA / "questions.json").read_text(encoding="utf-8"))
        hashes = {"corpusSha256": evaluation.hashlib.sha256((evaluation.DATA / "corpus.json").read_bytes()).hexdigest(),
                  "questionsSha256": evaluation.hashlib.sha256((evaluation.DATA / "questions.json").read_bytes()).hexdigest()}
        rows = [{"datasetVersion": "onda-v1", **hashes, "questionId": q["id"], "experiment": experiment,
                 "live": False, "candidateIds": [], "selectedIds": [], "latencyMillis": 1,
                 "expansionModelCalls": 0, "embeddingCalls": 0, "answerModelCalls": 0, "rerankerCalls": 0,
                 "fallback": False, "answer": ""} for experiment in evaluation.EXPERIMENTS for q in fixture["questions"]]
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "observations.jsonl"
            path.write_text("\n".join(json.dumps(row) for row in rows), encoding="utf-8")
            output = Path(folder) / "result"
            summaries = evaluation.evaluate(path, None, output)
            self.assertEqual(set(evaluation.EXPERIMENTS), set(summaries))
            for name in ("multi_query", "hyde"):
                report = json.loads((output / (name + "-approval.json")).read_text(encoding="utf-8"))
                self.assertFalse(evaluation.permits(report))
                self.assertIsNone(report["candidate"]["groundedness"])
            path.write_text(json.dumps(rows[0]), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Exactly one"):
                evaluation.evaluate(path, None, output)


if __name__ == "__main__":
    unittest.main()
