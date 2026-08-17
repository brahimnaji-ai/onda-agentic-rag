package ma.onda.rag.user.application;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ma.onda.rag.shared.exception.BusinessException;
import ma.onda.rag.shared.exception.ErrorCode;
import ma.onda.rag.user.api.UserResponse;
import ma.onda.rag.user.domain.User;
import ma.onda.rag.user.infra.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    public User findEntityByKeycloakId(String keycloakId){
        log.debug("Resolving local user for keycloakId='{}'", keycloakId);

        return userRepository.findByKeycloakId(keycloakId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_REGISTERED, keycloakId));
    }

    public UserResponse getProfileByKeycloakId(String keycloakId){
        User user = findEntityByKeycloakId(keycloakId);
        return toResponse(user);
    }

    private UserResponse toResponse(User user){
        return new UserResponse(
            user.getId(),
            user.getKeycloakId(),
            user.getUsername(),
            user.getEmail(),
            user.getFirstName(),
            user.getLastName(),
            user.getCreatedAt()
        );
    }
}
