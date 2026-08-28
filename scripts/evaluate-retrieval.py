"""Score frozen retrieval observations; unknown answer quality is null, never inferred from recall.

Usage: python scripts/evaluate-retrieval.py --observations target/deep-evaluation.jsonl
       [--reviews evaluation/onda-v1/reviews.json] [--output target/deep-evaluation]
Only complete paired runs can produce an approval artifact. Generated artifacts
remain unapproved until the operator reviews the labels and agrees the limits.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "evaluation" / "onda-v1"
EXPERIMENTS = ("FAST", "BALANCED", "DEEP_MULTI_QUERY", "DEEP_HYDE")


def unique(items):
    return list(dict.fromkeys(items))


def ranking(question, observation):
    relevance = {item["chunkId"]: item["grade"] for item in question["relevance"] if item["grade"] > 0}
    if not relevance:
        return None  # Unanswerable queries must not inflate recall or nDCG.
    recall = len(set(unique(observation["candidateIds"])[:20]) & relevance.keys()) / len(relevance)
    selected = unique(observation["selectedIds"])[:4]
    dcg = sum((2 ** relevance.get(chunk, 0) - 1) / math.log2(i + 2) for i, chunk in enumerate(selected))
    ideal = sum((2 ** grade - 1) / math.log2(i + 2) for i, grade in enumerate(sorted(relevance.values(), reverse=True)[:4]))
    reciprocal = next((1 / (i + 1) for i, chunk in enumerate(selected) if chunk in relevance), 0)
    return recall, dcg / ideal, reciprocal


def mean(values):
    return sum(values) / len(values) if values else None


def p95(values):
    return sorted(values)[math.ceil(0.95 * len(values)) - 1] if values else None


def validate_fixture(corpus, fixture):
    chunks = {chunk["id"]: chunk for chunk in corpus["chunks"]}
    assert len(chunks) == len(corpus["chunks"]), "Duplicate corpus IDs"
    questions = fixture["questions"]
    assert 50 <= len(questions) <= 100
    assert len({q["id"] for q in questions}) == len(questions)
    assert {q["language"] for q in questions} == {"fr", "en", "ar"}
    assert {"airport-code", "policy-reference", "follow-up", "multi-part", "unanswerable"} <= {tag for q in questions for tag in q["tags"]}
    for q in questions:
        assert q["answerable"] == bool(q["relevance"])
        assert "answer" not in q and "expectedAnswer" not in q
        for label in q["relevance"]:
            assert label["chunkId"] in chunks
            assert chunks[label["chunkId"]]["documentId"] == label["documentId"]
            assert label["grade"] > 0


def summarize(questions, rows, reviews):
    metrics = [(q, row, ranking(q, row)) for q, row in zip(questions, rows)]
    judged = [reviews[(row["experiment"], q["id"])] for q, row in zip(questions, rows)
              if (row["experiment"], q["id"]) in reviews]
    return {"questions": len(rows), "reviewedAnswers": len(judged),
            "recall20": mean([rank[0] for _, _, rank in metrics if rank is not None]),
            "ndcg4": mean([rank[1] for _, _, rank in metrics if rank is not None]),
            "mrr4": mean([rank[2] for _, _, rank in metrics if rank is not None]),
            "hardNdcg4": mean([rank[1] for q, _, rank in metrics if q["hard"] and rank is not None]),
            "citationCorrectness": mean([r["citationCorrectness"] for r in judged]) if len(judged) == len(rows) else None,
            "groundedness": mean([r["groundedness"] for r in judged]) if len(judged) == len(rows) else None,
            "p95Millis": p95([r["latencyMillis"] for r in rows]),
            "meanModelCalls": mean([r["expansionModelCalls"] for r in rows]),
            "meanEmbeddingCalls": mean([r["embeddingCalls"] for r in rows]),
            "meanAnswerModelCalls": mean([r["answerModelCalls"] for r in rows]),
            "meanRerankerCalls": mean([r["rerankerCalls"] for r in rows]),
            "fallbackRate": mean([int(r["fallback"]) for r in rows]),
            "unanswerableCount": sum(not q["answerable"] for q in questions),
            "unanswerableAbstentionRate": mean([reviews[(row["experiment"], q["id"])]["abstained"]
                for q, row in zip(questions, rows) if not q["answerable"] and (row["experiment"], q["id"]) in reviews])}


def permits(report):
    base, candidate, limits = report["baseline"], report["candidate"], report["limits"]
    if not all((report["live"], report["labelsReviewed"], report["budgetApproved"], report["approvedBy"],
                candidate["reviewedAnswers"] == candidate["questions"], base["reviewedAnswers"] == base["questions"])):
        return False
    return (candidate["hardNdcg4"] - base["hardNdcg4"] >= limits["minimumHardNdcgGain"]
            and candidate["recall20"] >= base["recall20"]
            and candidate["p95Millis"] <= min(limits["maxP95Millis"], base["p95Millis"] * limits["maxP95Ratio"])
            and candidate["meanModelCalls"] <= limits["maxMeanModelCalls"]
            and candidate["meanRerankerCalls"] <= limits["maxMeanRerankerCalls"]
            and candidate["fallbackRate"] <= limits["maxFallbackRate"]
            and candidate["citationCorrectness"] >= max(base["citationCorrectness"], limits["minimumCitationCorrectness"])
            and candidate["groundedness"] >= max(base["groundedness"], limits["minimumGroundedness"]))


def evaluate(observations, review_path, output):
    corpus = json.loads((DATA / "corpus.json").read_text(encoding="utf-8"))
    fixture = json.loads((DATA / "questions.json").read_text(encoding="utf-8"))
    policy = json.loads((DATA / "limits.json").read_text(encoding="utf-8"))
    validate_fixture(corpus, fixture)
    hashes = {"corpusSha256": hashlib.sha256((DATA / "corpus.json").read_bytes()).hexdigest(),
              "questionsSha256": hashlib.sha256((DATA / "questions.json").read_bytes()).hexdigest()}
    rows = [json.loads(line) for line in observations.read_text(encoding="utf-8").splitlines() if line.strip()]
    by_key = {(row["experiment"], row["questionId"]): row for row in rows}
    expected = {(experiment, q["id"]) for experiment in EXPERIMENTS for q in fixture["questions"]}
    if len(rows) != len(by_key) or by_key.keys() != expected:
        raise ValueError("Exactly one observation per question per experiment is required; no duplicate or partial runs")
    chunk_ids = {c["id"] for c in corpus["chunks"]}
    for row in rows:
        if row["datasetVersion"] != fixture["version"] or any(row[key] != value for key, value in hashes.items()):
            raise ValueError("Observation belongs to a different corpus or label version")
        if set(row["candidateIds"] + row["selectedIds"]) - chunk_ids:
            raise ValueError("Evidence contains an ID outside the frozen real corpus")
        for key in ("latencyMillis", "expansionModelCalls", "embeddingCalls", "answerModelCalls", "rerankerCalls"):
            if not isinstance(row[key], (int, float)) or not math.isfinite(row[key]) or row[key] < 0:
                raise ValueError("Non-finite or negative observation")
    reviews = {}
    if review_path:
        for review in json.loads(review_path.read_text(encoding="utf-8")):
            key = (review["experiment"], review["questionId"])
            if key not in by_key or key in reviews or not review.get("reviewer") or not by_key[key].get("answer"):
                raise ValueError("Reviews require a unique real generated answer and an identified reviewer")
            for field in ("citationCorrectness", "groundedness"):
                if not 0 <= review[field] <= 1:
                    raise ValueError("Judgment must be between zero and one")
            if not isinstance(review.get("abstained"), bool):
                raise ValueError("Reviews must explicitly mark abstention")
            digest = hashlib.sha256(by_key[key]["answer"].encode("utf-8")).hexdigest()
            if review.get("answerSha256") != digest:
                raise ValueError("Review does not match the generated answer")
            reviews[key] = review
    summaries = {experiment: summarize(fixture["questions"],
                [by_key[experiment, q["id"]] for q in fixture["questions"]], reviews) for experiment in EXPERIMENTS}
    output.mkdir(parents=True, exist_ok=True)
    (output / "metrics.json").write_text(json.dumps(summaries, indent=2) + "\n", encoding="utf-8")
    for strategy in ("MULTI_QUERY", "HYDE"):
        report = {"datasetVersion": fixture["version"], **hashes, "live": all(row.get("live") is True for row in rows),
                  "labelsReviewed": fixture["labelsReviewed"], **policy, "strategy": strategy,
                  "baseline": summaries["BALANCED"], "candidate": summaries["DEEP_" + strategy]}
        # Java's gate consumes this strict schema; extra display metrics are kept in metrics.json.
        allowed = {"questions", "reviewedAnswers", "recall20", "hardNdcg4", "citationCorrectness", "groundedness",
                   "p95Millis", "meanModelCalls", "meanRerankerCalls", "fallbackRate"}
        report["baseline"] = {k: v for k, v in report["baseline"].items() if k in allowed}
        report["candidate"] = {k: v for k, v in report["candidate"].items() if k in allowed}
        (output / (strategy.lower() + "-approval.json")).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(strategy + ": " + ("ELIGIBLE FOR OPERATOR PROMOTION" if permits(report) else "KEEP BALANCED; DEEP DISABLED"))
    return summaries


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--observations", type=Path, required=True)
    parser.add_argument("--reviews", type=Path)
    parser.add_argument("--output", type=Path, default=ROOT / "target" / "deep-evaluation")
    args = parser.parse_args()
    evaluate(args.observations, args.reviews, args.output)
