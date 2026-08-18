package ma.onda.rag.conversation.api;

import ma.onda.rag.conversation.api.dto.ConversationDetailResponse;
import ma.onda.rag.conversation.api.dto.ConversationResponse;
import ma.onda.rag.conversation.application.ConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Conversation management endpoints for the authenticated caller.
 *
 * <table border="1">
 *   <tr><th>Method</th><th>Path</th><th>Auth</th><th>Returns</th></tr>
 *   <tr><td>POST</td><td>/api/v1/conversations</td><td>ROLE_USER</td><td>201 ConversationResponse</td></tr>
 *   <tr><td>GET</td><td>/api/v1/conversations</td><td>ROLE_USER</td><td>200 List&lt;ConversationResponse&gt;</td></tr>
 *   <tr><td>GET</td><td>/api/v1/conversations/{conversationId}</td><td>ROLE_USER</td><td>200 ConversationDetailResponse</td></tr>
 *   <tr><td>DELETE</td><td>/api/v1/conversations/{conversationId}</td><td>ROLE_USER</td><td>204</td></tr>
 * </table>
 *
 * <p>Validation errors return HTTP 400 with an RFC 7807 {@code ProblemDetail}
 * body, handled by Spring's built-in {@code DefaultHandlerExceptionResolver} in
 * combination with the project's {@link ma.onda.rag.shared.handler.GlobalExceptionHandler}.
 */


@RestController
@RequestMapping("/api/v1/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    /** Creates a thread; a missing title receives the standard default title. */

    @PostMapping
    public ResponseEntity<ConversationResponse> createConversation(
            @RequestParam(required = false) String title
    ) {
        ConversationResponse response = conversationService.createConversation(title);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{conversationId}")
                .buildAndExpand(response.id())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping
    public ResponseEntity<List<ConversationResponse>> getUserConversations() {
        return ResponseEntity.ok(conversationService.getUserConversations());
    }

    @GetMapping("/{conversationId}")
    public ResponseEntity<ConversationDetailResponse> getConversationDetails(
            @PathVariable("conversationId") UUID conversationId) {
        return ResponseEntity.ok(conversationService.getConversationDetails(conversationId));
    }

    @DeleteMapping("/{conversationId}")
    public ResponseEntity<Void> deleteConversation(@PathVariable("conversationId") UUID conversationId) {
        conversationService.deleteConversation(conversationId);
        return ResponseEntity.noContent().build();
    }
}
