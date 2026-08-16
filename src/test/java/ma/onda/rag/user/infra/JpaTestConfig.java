package ma.onda.rag.user.infra;

import ma.onda.rag.shared.persistance.JpaAuditingConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Imports {@link JpaAuditingConfig} into the {@code @DataJpaTest} slice.
 *
 * <p>{@code @DataJpaTest} loads only a minimal JPA context and excludes
 * {@code @Configuration} classes from the main source set. As a result,
 * {@code @EnableJpaAuditing} is missing and the {@link
 * org.springframework.data.jpa.domain.support.AuditingEntityListener}
 * cannot populate {@code createdAt} / {@code lastModifiedAt}, causing a
 * {@code NOT NULL} constraint violation on every {@code save()}.
 *
 * <p>Importing {@code JpaAuditingConfig} here re-enables auditing for the
 * slice without pulling in the full application context.
 */
@TestConfiguration
@Import(JpaAuditingConfig.class)
public class JpaTestConfig {
}
