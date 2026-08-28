package ma.onda.rag.agent.infra.springai;

import ma.onda.rag.agent.infra.retrieval.DeepQueryExpander.Strategy;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DeepApprovalTest {
    private static final DeepApproval.Limits LIMITS = new DeepApproval.Limits(.05, 5000, 2, 1, 1, .05, .95, .95);
    private static final DeepApproval.Metrics BASE = metrics(.6, 2000, 0, 0);

    @Test
    void onlyReviewedLiveIndependentExperimentWithinEveryLimitCanBePromoted() {
        var candidate = metrics(.7, 3000, 1, .02);
        assertThat(report(true, true, true, candidate).permits(Strategy.MULTI_QUERY)).isTrue();
        assertThat(report(true, true, true, candidate).permits(Strategy.HYDE)).isFalse();
        assertThat(report(false, true, true, candidate).permits(Strategy.MULTI_QUERY)).isFalse();
        assertThat(report(true, false, true, candidate).permits(Strategy.MULTI_QUERY)).isFalse();
        assertThat(report(true, true, false, candidate).permits(Strategy.MULTI_QUERY)).isFalse();
        for (var bad : java.util.List.of(metrics(.62, 3000, 1, 0), metrics(.7, 4500, 1, 0),
                metrics(.7, 3000, 2, 0), metrics(.7, 3000, 1, .1), metrics(Double.NaN, 1000, 0, 0))) {
            assertThat(report(true, true, true, bad).permits(Strategy.MULTI_QUERY)).isFalse();
        }
        var unknown = new DeepApproval.Metrics(60, 0, .8, .7, null, null, 1000., 1., 0., 0.);
        assertThat(report(true, true, true, unknown).permits(Strategy.MULTI_QUERY)).isFalse();
    }

    private DeepApproval report(boolean live, boolean reviewed, boolean agreed, DeepApproval.Metrics candidate) {
        return new DeepApproval("onda-v1", "a".repeat(64), "b".repeat(64), live, reviewed, agreed,
                "test-reviewer", Strategy.MULTI_QUERY, BASE, candidate, LIMITS);
    }

    private static DeepApproval.Metrics metrics(double quality, double latency, double calls, double fallback) {
        return new DeepApproval.Metrics(60, 60, .8, quality, .98, .98, latency, calls, 0., fallback);
    }
}
