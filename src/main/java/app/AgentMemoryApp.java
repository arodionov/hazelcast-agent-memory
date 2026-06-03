package app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hazelcast.config.Config;
import com.hazelcast.config.vector.Metric;
import com.hazelcast.config.vector.VectorCollectionConfig;
import com.hazelcast.config.vector.VectorIndexConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.hazelcast.vector.SearchOptions;
import com.hazelcast.vector.SearchResult;
import com.hazelcast.vector.SearchResults;
import com.hazelcast.vector.VectorCollection;
import com.hazelcast.vector.VectorDocument;
import com.hazelcast.vector.VectorValues;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

/**
 * Minimal Hazelcast equivalent of the Redis agent-memory-server Quick Start.
 * <p>
 * Embeddings : Ollama nomic-embed-text (free, local, 768-dim)
 * Chat       : Ollama llama3.2         (free, local)
 * <p>
 * Prerequisites:
 * - Ollama running on localhost:11434
 * ollama pull nomic-embed-text
 * ollama pull llama3.2
 * - HZ_LICENSEKEY env var (Hazelcast Enterprise — required for VectorCollection)
 */
public class AgentMemoryApp {

    private static final String INDEX_NAME = "idx";
    private static final int DIMENSIONS = 768;

    private static final String EMBED_URL = "http://localhost:11434/api/embeddings";
    private static final String EMBED_MODEL = "nomic-embed-text";

    private static final String CHAT_URL = "http://localhost:11434/api/chat";
    private static final String CHAT_MODEL = "llama3.2";

    // Value is a JSON string: { "text": "...", "userId": "...", "memoryType": "...", "topics": [...] }
    private final VectorCollection<String, String> longTermMemory;
    private final IMap<String, List<Map<String, String>>> workingMemory;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentMemoryApp(HazelcastInstance hz) {
        this.longTermMemory = VectorCollection.getCollection(
                hz,
                new VectorCollectionConfig("long-term-memory")
                        .setBackupCount(0)
                        .addVectorIndexConfig(new VectorIndexConfig()
                                .setName(INDEX_NAME)
                                .setDimension(DIMENSIONS)
                                .setMetric(Metric.COSINE))
        );
        this.workingMemory = hz.getMap("working-memory");
    }

    void createLongTermMemory(String text, String userId, String memoryType,
                              List<String> topics) throws Exception {
        // Store metadata as JSON — so topics/userId survive and print in search
        float[] vector = embed(text);
        String json = toJson(text, userId, memoryType, topics);

        longTermMemory
                .setAsync(UUID.randomUUID().toString(),
                        VectorDocument.of(json, VectorValues.of(INDEX_NAME, vector)))
                .toCompletableFuture().join();

        System.out.printf("  [stored] %s | topics=%s%n", text, topics);
    }

    List<String> searchLongTermMemory(String text, int limit) throws Exception {
        SearchResults<String, String> results = longTermMemory
                .searchAsync(
                        VectorValues.of(INDEX_NAME, embed(text)),
                        SearchOptions.builder().limit(limit).includeValue().build()
                )
                .toCompletableFuture().join();

        List<String> found = new ArrayList<>();
        for (Iterator<SearchResult<String, String>> it = results.results(); it.hasNext(); ) {
            SearchResult<String, String> r = it.next();
            JsonNode meta = mapper.readTree(r.getValue());
            String memory = meta.path("text").asText();
            String topics = meta.path("topics").toString();

            System.out.printf("  relevance=%.2f | %s | topics=%s%n",
                    r.getScore(), memory, topics);
            found.add(memory);
        }
        return found;
    }

    void putWorkingMemory(String sessionId, List<Map<String, String>> messages) {
        workingMemory.put(sessionId, messages);
    }

    List<Map<String, String>> memoryPrompt(String query, String sessionId,
                                           int limit) throws Exception {
        List<String> memories = searchLongTermMemory(query, limit);

        StringBuilder system = new StringBuilder("You are a helpful assistant.\n");
        if (!memories.isEmpty()) {
            system.append("\nRelevant facts about this user:\n");
            memories.forEach(m -> system.append("- ").append(m).append("\n"));
        }

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", system.toString()));
        messages.addAll(workingMemory.getOrDefault(sessionId, List.of()));
        return messages;
    }

    String chatWithMemory(String userMessage, String sessionId) throws Exception {
        List<Map<String, String>> context = memoryPrompt(userMessage, sessionId, 5);

        String systemPrompt = context.stream()
                .filter(m -> "system".equals(m.get("role")))
                .map(m -> m.get("content"))
                .findFirst().orElse("You are a helpful assistant.");

        List<Map<String, String>> chatMessages = new ArrayList<>(
                context.stream().filter(m -> !"system".equals(m.get("role"))).toList()
        );
        chatMessages.add(Map.of("role", "user", "content", userMessage));

        String reply = callOllamaChat(systemPrompt, chatMessages);

        List<Map<String, String>> history =
                new ArrayList<>(workingMemory.getOrDefault(sessionId, List.of()));
        history.add(Map.of("role", "user", "content", userMessage));
        history.add(Map.of("role", "assistant", "content", reply));
        putWorkingMemory(sessionId, history);

        return reply;
    }

    static void main() throws Exception {
        System.out.println("Starting embedded Hazelcast server...");

        Config config = new Config();
        config.setLicenseKey(System.getenv("HZ_LICENSEKEY"));

        HazelcastInstance hz = Hazelcast.newHazelcastInstance(config);
        System.out.println("✅ Hazelcast ready: " + hz.getCluster().getLocalMember().getAddress());

        AgentMemoryApp app = new AgentMemoryApp(hz);

        try {
            System.out.println("\n── Creating long-term memories ──");
            app.createLongTermMemory(
                    "Alice works as a software engineer specializing in Python and web development",
                    "alice", "semantic", List.of("career", "programming", "python"));
            app.createLongTermMemory(
                    "Alice prefers morning meetings and hates scheduling calls after 4 PM",
                    "alice", "semantic", List.of("scheduling", "preferences", "work"));

            app.createLongTermMemory(
                    "Paul works as a QA engineer specializing in web testing and automation",
                    "paul", "semantic", List.of("career", "qa", "automation"));
            app.createLongTermMemory(
                    "Paul prefers day meetings and doesn't like calls scheduled on early morning",
                    "paul", "semantic", List.of("scheduling", "preferences", "work"));

            System.out.println("\n── Searching long-term memory ──");
            System.out.println("Query: 'Alice work preferences and schedule'");
            app.searchLongTermMemory("Alice work preferences and schedule", 5);

            System.out.println("\n── Memory-enhanced conversation ──");

            String r0 = app.chatWithMemory(
                    "Hi! I am Alice", "my-session-123");
            System.out.println("\nUser : Hi! I am Alice");
            System.out.println("AI   : " + r0);

            String r1 = app.chatWithMemory(
                    "I love Italian food, especially pasta like carbonara", "my-session-123");
            System.out.println("\nUser : I love Italian food, especially pasta like carbonara");
            System.out.println("AI   : " + r1);

            String r2 = app.chatWithMemory(
                    "Can you recommend a good recipe for dinner?", "my-session-123");
            System.out.println("\nUser : Can you recommend a good recipe for dinner?");
            System.out.println("AI   : " + r2);

        } finally {
            hz.shutdown();
        }
    }

    private float[] embed(String text) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", EMBED_MODEL);
        body.put("prompt", text);

        String embeddingResponse = post(EMBED_URL, body);
        ArrayNode embedding = (ArrayNode) mapper.readTree(embeddingResponse).path("embedding");
        float[] v = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) v[i] = (float) embedding.get(i).asDouble();
        return v;
    }

    private String toJson(String text, String userId, String memoryType, List<String> topics) throws JsonProcessingException {
        ObjectNode meta = mapper.createObjectNode();
        meta.put("text", text);
        meta.put("userId", userId);
        meta.put("memoryType", memoryType);
        meta.putPOJO("topics", topics);
        String json = mapper.writeValueAsString(meta);
        return json;
    }

    private String callOllamaChat(String systemPrompt,
                                  List<Map<String, String>> messages) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", CHAT_MODEL);
        body.put("stream", false);

        ArrayNode msgs = body.putArray("messages");
        msgs.addObject().put("role", "system").put("content", systemPrompt);
        for (Map<String, String> m : messages)
            msgs.addObject().put("role", m.get("role")).put("content", m.get("content"));

        String chatResponse = post(CHAT_URL, body);
        return mapper.readTree(chatResponse)
                .path("message").path("content").asText();
    }

    private String post(String url, ObjectNode body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200)
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
        return resp.body();
    }
}