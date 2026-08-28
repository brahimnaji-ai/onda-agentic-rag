# ONDA evaluation set v1

60 draft-labeled questions; 76 overlapping chunks from ten actual local ONDA PDFs.
Languages: French, Arabic and English. Categories include airport codes, exact
ethics sections, follow-ups (with prior user turns), multi-part and unanswerable
questions. Six questions are unanswerable **within this frozen corpus**.

## Provenance and label review

`corpus.json` contains source filenames, SHA-256 checksums, physical PDF page
numbers, extracted text, stable document IDs and stable chunk IDs. Public press
contact details are omitted. Source PDFs are not copied into Git. The rebuild
script reads the existing ignored `documents/onda` folder and uses `pypdf`:

```powershell
python scripts/build-eval-corpus.py
python -B scripts/test_evaluate_retrieval.py
```

IDs are UUIDv5 values derived from PDF content hash, page and chunk position, not
questions or expected answers. This is an isolated benchmark namespace; these are
**not** the random ingestion IDs in an existing production database. The live
runner seeds exactly these IDs into a temporary PostgreSQL container.

Questions reference document/chunk IDs and relevance grades, never an expected
answer string. Overlapping chunks may both be relevant. Grade 1 means relevant;
0 means not relevant. Edit graded labels after inspecting the full cited PDF page,
not after seeing which profile retrieved it. A bilingual ONDA reviewer should check
Arabic translations, completeness and negative labels. `labelsReviewed` is false
until that review. Review and freeze labels **before** collecting observations:
changing the label file changes its checksum and invalidates an earlier run.

The older press releases and code excerpts describe their documents' claims; do
not treat them as a verified statement of today's policy. A future corpus/label
change should create `onda-v2` and a new baseline rather than overwrite v1 results.

## Independent live experiments

The opt-in runner uses the same pipeline, post-processor and Spring AI expansion
adapters as the application. It uses a new disposable `pgvector/pgvector:pg17`
container, so it never writes to the application's PostgreSQL database. It requires
Docker, local Ollama with `nomic-embed-text` (768 dimensions), and an explicitly set
`GEMINI_API_KEY`. It does not automatically read `.env`.

**External use and cost:** enabling the runner sends query/history text to Gemini;
enabling answer generation also sends selected source excerpts. Optional Cohere
reranking sends candidate excerpts. Obtain data-handling and cost approval first.
For 60 questions, the run can make up to 120 expansion calls, roughly 420 query
embedding calls plus 76 corpus embeddings, optionally 240 answer calls and up to
240 reranker calls. Counts are logical model calls; SDK retries can add provider
requests and billing. No generation cost or dollar total is invented from call counts.

```powershell
# Set GEMINI_API_KEY securely in your shell first; do not paste it into source files.
ollama pull nomic-embed-text
.\mvnw.cmd '-Dtest=DeepRetrievalBenchmarkTest' '-Donda.eval.live=true' '-Donda.eval.answers=true' test
# Optional, with COHERE_API_KEY: add '-Donda.eval.rerank=true'.
python -B scripts/evaluate-retrieval.py --observations target/deep-evaluation.jsonl
```

Optional runner properties: `onda.eval.chat-model`, `onda.eval.embedding-model`,
`onda.eval.ollama-url`, `onda.eval.output`. Output uses CREATE_NEW: choose a new
output path for a rerun. A partial/duplicate run cannot produce an approval.

The four configurations are FAST (8 dense, threshold .5), BALANCED (20 dense + 20
lexical), DEEP_MULTI_QUERY (original + 2 variations), and DEEP_HYDE (original hybrid
search + one hypothetical dense search). Both DEEP variants use a 2-second expansion
timeout, 384 output-token cap, 4 final chunks and the same 4096 evidence-token budget.
The runner rotates experiment order per question to reduce fixed warm-cache bias.
It runs sequentially; p95 is retrieval latency after corpus indexing, **not** a
concurrent-load SLA or total answer latency. Repeat on production-like hardware and
load before accepting a promotion; a single 60-question run is exploratory.

## Metrics and answer judgments

Recall@20 uses distinct joined candidate IDs **before** final evidence selection.
nDCG@4 and MRR@4 use the selected evidence order. Unanswerable questions are excluded
from those ranking means and reported separately. `hardNdcg4` uses answerable hard
questions. p95 uses the nearest-rank percentile. Fallback rate counts expansion and
reranking fallback per retrieval. Embedding, expansion, answer and reranker call
counts are separate. `meanModelCalls` in approval files is the **additional expansion**
call budget; other call categories remain visible in `metrics.json`.

Citation correctness and groundedness are not inferred from retrieval relevance,
nonempty answers or the presence of source metadata. They remain null until each
generated answer is judged. A reviewer can use Spring AI's FactCheckingEvaluator as
a second opinion, but relevance-only judgments do not establish groundedness.

Create an array of 240 reviews (one per experiment/question), using actual answer
hashes from observations:

```json
[
  {
    "experiment": "BALANCED",
    "questionId": "bus-1",
    "reviewer": "reviewer-identifier",
    "answerSha256": "SHA256_OF_THE_EXACT_GENERATED_ANSWER_UTF8",
    "citationCorrectness": 1.0,
    "groundedness": 1.0,
    "abstained": false
  }
]
```

Citation correctness: fraction of explicit answer citations that identify supplied
real chunks and support the associated claim. Groundedness: fraction of verifiable
answer claims supported by supplied real evidence. For a correct abstention on an
unanswerable question with no factual assertions, assign both 1; an invented answer
must not receive that credit. Score incomplete answers conservatively. Do not assume
the API's `sources` array means the model actually cited each source.

```powershell
python -B scripts/evaluate-retrieval.py --observations target/deep-evaluation.jsonl --reviews evaluation/onda-v1/reviews.json
```

Do not commit raw model responses without review for sensitive content. The scorer
emits `metrics.json` and separate `multi_query-approval.json` / `hyde-approval.json`.
The proposed limits live in `limits.json` with `budgetApproved: false`. An operator
must agree those limits and identify `approvedBy`. Production rechecks every quality,
latency/call-count and fallback threshold, requires reviewed live results, and verifies
that the approval's strategy matches the selected strategy. A fabricated or hand-edited
"pass" label alone cannot enable DEEP. Approval files are operator-controlled config,
not cryptographic attestations; protect them and rebenchmark after changing models,
corpus, prompts, limits, hardware or retrieval settings.

Only after a passing review, start with exactly one strategy flag and its report:

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.arguments=--rag.retrieval.profile=DEEP --rag.retrieval.deep.enabled=true --rag.retrieval.deep.multi-query-enabled=true --rag.retrieval.deep.strategy=MULTI_QUERY --rag.retrieval.deep.approval-report=file:target/deep-evaluation/multi_query-approval.json'
```

For HyDE use `hyde-enabled=true`, `multi-query-enabled=false`, `strategy=HYDE`, and
`hyde-approval.json`. Never combine both in this first evaluation. Turning on flags
without a valid reviewed report fails startup; the committed production default is
BALANCED with all DEEP flags off.

References: [HyDE paper](https://arxiv.org/abs/2212.10496),
[Spring AI evaluation](https://docs.spring.io/spring-ai/reference/api/testing.html),
[MultiQueryExpander](https://docs.spring.io/spring-ai/docs/2.0.0/api/org/springframework/ai/rag/preretrieval/query/expansion/MultiQueryExpander.html).
