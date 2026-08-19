package ma.onda.rag.agent.infra.springai;

import ma.onda.rag.agent.infra.tools.VectorSearchTool;
import ma.onda.rag.agent.infra.tools.WebSearchTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    @Bean
    ChatClient chatClient(ChatClient.Builder builder, VectorSearchTool vectorSearchTool, WebSearchTool webSearchTool) {
        FunctionToolCallback<VectorSearchTool.Request, VectorSearchTool.Response> vectorSearchCallback =
                FunctionToolCallback.builder("vectorSearchTool", vectorSearchTool)
                        .description("Search PostgreSQL pgvector vector store for relevant document snippets")
                        .inputType(VectorSearchTool.Request.class)
                        .build();

        FunctionToolCallback<WebSearchTool.Request, WebSearchTool.Response> webSearchCallback =
                FunctionToolCallback.builder("webSearchTool", webSearchTool)
                        .description("Search the live web for external real-time information when internal context is insufficient")
                        .inputType(WebSearchTool.Request.class)
                        .build();

        return builder
                .defaultSystem("""
                You are the ONDA RAG Assistant, an intelligent enterprise AI assistant for the Office National Des Aéroports (ONDA).
                Your primary role is to assist users by providing precise, trustworthy, and context-grounded answers based on official enterprise knowledge.
                
                ### CORE RESPONSIBILITIES & TOOL USAGE
                - Utilize available tools (such as `VectorSearchTool`) to retrieve relevant enterprise documents and information.
                - If the prompt specifically asks for real-time external information or if `VectorSearchTool` returns insufficient context, dynamically use `WebSearchTool` to query live external web search APIs.
                - Synthesize retrieved content into clear, direct, and actionable responses.
                
                ### ACCURACY & GROUNDING CONSTRAINTS
                - Base your answers strictly on the retrieved document snippets, web results, and verified factual context.
                - Never invent, speculate, or extrapolate facts, policies, or metrics not grounded in the provided context.
                - If the retrieved information is incomplete, insufficient, or absent, state clearly that the available context does not contain enough detail to answer fully.
                - Respond politely to simple greetings or conversational cues without invoking unnecessary searches.
                
                ### RESPONSE FORMATTING & CITATIONS
                - Use professional, clear, and structured Markdown (headings, lists, bold emphasis).
                - Reference source document filenames or web source URLs when citing retrieved information to ensure full transparency and traceability.
                """)
                .defaultTools(vectorSearchCallback, webSearchCallback)
                .build();
    }
}
