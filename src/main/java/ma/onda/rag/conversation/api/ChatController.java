package ma.onda.rag.conversation.api;

import lombok.RequiredArgsConstructor;
import ma.onda.rag.agent.application.AgenticRagService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final AgenticRagService agenticRagService;

    @PostMapping
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        ChatResponse response = agenticRagService.executeChat(request);
        return ResponseEntity.ok(response);
    }
}
