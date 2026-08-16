package ma.onda.rag.user.infra;

import ma.onda.rag.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaTestConfig.class)
@DisplayName("UserRepository — custom finder methods")
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    // Fixture helper

    private User savedUser(String keycloakId, String username, String email) {
        User user = User.builder()
                .keycloakId(keycloakId)
                .username(username)
                .email(email)
                .firstName("Test")
                .lastName("User")
                .build();
        return userRepository.save(user);
    }

    // findByKeycloakId

    @Test
    @DisplayName("findByKeycloakId returns user when keycloakId matches")
    void findByKeycloakId_found() {
        savedUser("kc-sub-001", "kizaru", "b.naji@onda.com");

        Optional<User> result = userRepository.findByKeycloakId("kc-sub-001");

        assertThat(result).isPresent();
        assertThat(result.get().getUsername()).isEqualTo("kizaru");
    }

    @Test
    @DisplayName("findByKeycloakId returns empty when keycloakId is unknown")
    void findByKeycloakId_notFound() {
        Optional<User> result = userRepository.findByKeycloakId("unknown-sub");

        assertThat(result).isEmpty();
    }

    // findByEmailIgnoreCase

    @Test
    @DisplayName("findByEmailIgnoreCase is case-insensitive")
    void findByEmailIgnoreCase_caseInsensitive() {
        savedUser("kc-sub-002", "brahim", "B.naji@Onda.com");

        Optional<User> result = userRepository.findByEmailIgnoreCase("b.Naji@onda.com");

        assertThat(result).isPresent();
        assertThat(result.get().getUsername()).isEqualTo("brahim");
    }

    @Test
    @DisplayName("findByEmailIgnoreCase returns empty for unknown email")
    void findByEmailIgnoreCase_notFound() {
        Optional<User> result = userRepository.findByEmailIgnoreCase("g.ghost@onda.com");

        assertThat(result).isEmpty();
    }

    // existsByEmailIgnoreCase

    @Test
    @DisplayName("existsByEmailIgnoreCase returns true when email exists")
    void existsByEmailIgnoreCase_true() {
        savedUser("kc-sub-003", "carol", "a.carol@onda.com");

        assertThat(userRepository.existsByEmailIgnoreCase("A.CAROL@ONDA.COM")).isTrue();
    }

    @Test
    @DisplayName("existsByEmailIgnoreCase returns false when email is absent")
    void existsByEmailIgnoreCase_false() {
        assertThat(userRepository.existsByEmailIgnoreCase("nobody@onda.com")).isFalse();
    }

    // existsByUsername

    @Test
    @DisplayName("existsByUsername returns true when username exists")
    void existsByUsername_true() {
        savedUser("kc-sub-004", "dave", "k.dave@onda.com");

        assertThat(userRepository.existsByUsername("dave")).isTrue();
    }

    @Test
    @DisplayName("existsByUsername returns false when username is absent")
    void existsByUsername_false() {
        assertThat(userRepository.existsByUsername("ghost")).isFalse();
    }
}
