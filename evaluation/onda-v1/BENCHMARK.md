# DEEP benchmark decision - 2026-08-28

**Decision: retain BALANCED; DEEP is disabled.**

Dataset: `onda-v1`, 60 questions, 76 real-source chunks, ten ONDA PDFs.
Labels and source checksums are versioned independently of generated answers.
Labels are draft and require ONDA/bilingual review. Proposed budgets are unapproved.

| Experiment | Recall@20 / nDCG@4 / MRR@4 | Citation correctness / groundedness | p95 / calls / fallback |
|---|---|---|---|
| FAST | Live run pending | Answer review pending | Live run pending |
| BALANCED | Live run pending | Answer review pending | Live run pending |
| DEEP_MULTI_QUERY | Live run pending | Answer review pending | Live run pending |
| DEEP_HYDE | Live run pending | Answer review pending | Live run pending |

No simulated ranking, stub-model result, or unit-test duration is presented as
model quality, production latency, or cost evidence. The implementation provides
an isolated live runner for all four experiments and a strict paired-run scorer.
Live external-model benchmarking was not run without agreed cost/data-handling
approval. Until a reviewed run demonstrates a gain, the promotion gate stays closed.

Proposed thresholds (not yet agreed): hard-query nDCG@4 gain at least +0.05 absolute,
no Recall@20 regression, p95 retrieval <= 5000 ms and <= 2x BALANCED, at most one
additional expansion model call and one reranker call per retrieval, fallback <= 5%,
and citation correctness/groundedness >= 95% with no baseline regression.

Selected production defaults: BALANCED, 20 dense + 20 lexical candidates, RRF k=60,
4 final evidence chunks, 4096 conservative context-token budget, rewriting off,
reranking opt-in, adjacent expansion off, DEEP/multi-query/HyDE flags off.

Safety verification covers original-query retention, independent strategy wiring,
bounded expansion/fallback, hypothetical-text exclusion from sources and diagnostics,
candidate-versus-selected ranking metrics, and rejection of unreviewed/over-budget
promotion reports. See [the evaluation procedure](README.md) for reproducing a live
run and replacing this pending measurement table with reviewed measured results.
