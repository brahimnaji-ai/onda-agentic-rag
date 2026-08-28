package ma.onda.rag.agent.infra.springai;

import ma.onda.rag.agent.infra.retrieval.DeepQueryExpander.Strategy;

/** Fail-closed promotion record produced by the evaluator and approved by the operator. */
public record DeepApproval(String datasetVersion, String corpusSha256, String questionsSha256,
                           boolean live, boolean labelsReviewed, boolean budgetApproved, String approvedBy,
                           Strategy strategy, Metrics baseline, Metrics candidate, Limits limits) {
    public record Metrics(int questions, int reviewedAnswers, Double recall20, Double hardNdcg4,
                          Double citationCorrectness, Double groundedness, Double p95Millis,
                          Double meanModelCalls, Double meanRerankerCalls, Double fallbackRate) {}
    public record Limits(double minimumHardNdcgGain, double maxP95Millis, double maxP95Ratio,
                         double maxMeanModelCalls, double maxMeanRerankerCalls, double maxFallbackRate,
                         double minimumCitationCorrectness, double minimumGroundedness) {}

    public boolean permits(Strategy requested) {
        if (!live || !labelsReviewed || !budgetApproved || approvedBy == null || approvedBy.isBlank()
                || datasetVersion == null || corpusSha256 == null || questionsSha256 == null
                || !corpusSha256.matches("[a-f0-9]{64}") || !questionsSha256.matches("[a-f0-9]{64}")
                || requested != strategy || limits == null || !complete(baseline) || !complete(candidate)
                || candidate.questions() != baseline.questions()) return false;
        return java.util.stream.DoubleStream.of(limits.minimumHardNdcgGain(), limits.maxP95Millis(), limits.maxP95Ratio(),
                        limits.maxMeanModelCalls(), limits.maxMeanRerankerCalls()).allMatch(Double::isFinite)
                && limits.minimumHardNdcgGain() > 0 && limits.maxP95Millis() > 0 && limits.maxP95Ratio() >= 1
                && limits.maxMeanModelCalls() >= 0 && limits.maxMeanRerankerCalls() >= 0
                && probability(limits.maxFallbackRate()) && probability(limits.minimumCitationCorrectness())
                && probability(limits.minimumGroundedness())
                && candidate.hardNdcg4() - baseline.hardNdcg4() >= limits.minimumHardNdcgGain()
                && candidate.recall20() >= baseline.recall20()
                && candidate.p95Millis() <= limits.maxP95Millis()
                && candidate.p95Millis() <= baseline.p95Millis() * limits.maxP95Ratio()
                && candidate.meanModelCalls() <= limits.maxMeanModelCalls()
                && candidate.meanRerankerCalls() <= limits.maxMeanRerankerCalls()
                && candidate.fallbackRate() <= limits.maxFallbackRate()
                && candidate.citationCorrectness() >= Math.max(baseline.citationCorrectness(), limits.minimumCitationCorrectness())
                && candidate.groundedness() >= Math.max(baseline.groundedness(), limits.minimumGroundedness());
    }

    private static boolean complete(Metrics value) {
        return value != null && value.questions() >= 50 && value.reviewedAnswers() == value.questions()
                && probability(value.recall20()) && probability(value.hardNdcg4())
                && probability(value.citationCorrectness()) && probability(value.groundedness())
                && probability(value.fallbackRate()) && nonnegative(value.p95Millis()) && value.p95Millis() > 0
                && nonnegative(value.meanModelCalls()) && nonnegative(value.meanRerankerCalls());
    }

    private static boolean probability(Double value) { return nonnegative(value) && value <= 1; }
    private static boolean nonnegative(Double value) { return value != null && Double.isFinite(value) && value >= 0; }
}
