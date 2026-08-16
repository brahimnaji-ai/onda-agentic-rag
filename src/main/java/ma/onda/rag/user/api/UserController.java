package ma.onda.rag.user.api;


import lombok.RequiredArgsConstructor;
import ma.onda.rag.user.application.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * Returns the profile of the currently authenticated user.
     *
     * <p>The Keycloak {@code sub} claim identifies the caller; Spring Security
     * validates the JWT before this method is reached.
     *
     * @param jwt the validated JWT injected by Spring Security
     * @return 200 OK with the caller's {@link UserResponse}
     */

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser(
            @AuthenticationPrincipal Jwt jwt
    ){
        String keycloakId = jwt.getSubject();
        UserResponse response = userService.getProfileByKeycloakId(keycloakId);
        return ResponseEntity.ok(response);
    }
}
