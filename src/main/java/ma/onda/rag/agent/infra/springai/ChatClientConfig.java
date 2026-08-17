package ma.onda.rag.agent.infra.springai;


import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    @Bean
    ChatClient chatClient(ChatClient.Builder builder){
         return builder
                .defaultSystem("""
                You are ONDA RAG Assistant, an enterprise AI assistant.
                Answer clearly, accurately, and concisely using the provided context.
                Do not invent information. If the context is insufficient, say so.
                """)
                .build();
    }
}
