package ma.onda.rag.agent.infra.springai;

import ma.onda.rag.agent.infra.retrieval.DeepQueryExpander.Strategy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

@ConfigurationProperties("rag.retrieval.deep")
public record DeepRetrievalProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("false") boolean multiQueryEnabled,
        @DefaultValue("false") boolean hydeEnabled,
        @DefaultValue("MULTI_QUERY") Strategy strategy,
        @DefaultValue("") String approvalReport,
        @DefaultValue("2s") Duration expansionTimeout,
        @DefaultValue("2") int variants,
        @DefaultValue("384") int maxOutputTokens,
        @DefaultValue("2") int maxConcurrentCalls) {
    public DeepRetrievalProperties {
        if (multiQueryEnabled && hydeEnabled) throw new IllegalArgumentException("Multi-query and HyDE must be separate experiments");
        if (enabled && (!(strategy == Strategy.MULTI_QUERY ? multiQueryEnabled : hydeEnabled)
                || approvalReport == null || approvalReport.isBlank())) {
            throw new IllegalArgumentException("DEEP requires its strategy flag and a passing reviewed approval report");
        }
        if (variants < 1 || variants > 3 || maxOutputTokens < 1 || maxOutputTokens > 1024 || maxConcurrentCalls < 1
                || expansionTimeout == null || expansionTimeout.isNegative() || expansionTimeout.isZero()) {
            throw new IllegalArgumentException("Invalid bounded DEEP expansion settings");
        }
    }
}
