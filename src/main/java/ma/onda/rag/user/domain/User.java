package ma.onda.rag.user.domain;


import jakarta.persistence.*;
import lombok.*;
import ma.onda.rag.conversation.domain.Conversation;
import ma.onda.rag.document.domain.DocumentEntity;
import ma.onda.rag.shared.persistance.BaseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;


/**
 * Local user mirror of a Keycloak principal.
 *
 * <p>We keep a local row per user so that domain objects (Conversation,
 * Document) can reference a stable, database-side FK rather than a raw
 * Keycloak {@code sub} string. The {@code keycloakId} is the authoritative
 * link to the IdP and is used for BOLA ownership checks.
 */

@Entity
@Table(name = "users")
@Getter @Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class User extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "keycloak_id", nullable = false, unique = true)
    private String keycloakId;         // Maps to JWT 'sub' claim (Keycloak UUID)

    @Column(name = "username", nullable = false, unique = true)
    private String username;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;


    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Conversation> conversations = new ArrayList<>();

    @OneToMany(mappedBy = "uploadedBy")
    @Builder.Default
    private List<DocumentEntity> documentEntities = new ArrayList<>();
}
