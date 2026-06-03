# Hazelcast Agent Memory – Java Quick-Start

Java equivalent of the [Redis Agent Memory Server Python quick-start](https://redis.github.io/agent-memory-server/quick-start/), rebuilt using:

- **Hazelcast Enterprise 5.7** `VectorCollection` (ANN search via JVector/DiskANN)
- **Ollama `nomic-embed-text`** (768-dim vectors, free, local)
- **Ollama `llama3.2`** for the chat layer (free, local)

No cloud API keys required.

---

## Architecture

```
User message
    │
    ▼
embed("search_query: " + text)
    │   Ollama nomic-embed-text (768-dim, asymmetric)
    ▼
AgentMemoryApp
    ├── VectorCollection "long-term-memory"   ← persistent facts (ANN search)
    │       index: idx (COSINE, 768-dim)
    │       value: JSON { text, userId, memoryType, topics, entities }
    │       stored as: embed("search_document: " + text)
    │
    └── IMap "working-memory"                 ← session conversation history
            key: sessionId
            value: List<Map<role, content>>
    │
    ▼
memoryPrompt()                        ← you write this (Redis does it server-side)
    ├── searchLongTermMemory()        → ANN search, userId filter, relevance threshold
    ├── inject memories into system prompt
    ├── append session history from IMap
    └── call Ollama llama3.2 /api/chat
```

---
## Mapping to Redis Agent Memory Server

| Redis Agent Memory Server  | Hazelcast Implementation           |
| -------------------------- | ---------------------------------- |
| Session Memory             | `IMap<String, List<Message>>`      |
| Long-Term Memory           | `VectorCollection<String, String>` |
| Embeddings                 | Ollama `nomic-embed-text`          |
| Vector Search              | Hazelcast ANN Search               |
| Chat Model                 | Ollama `llama3.2`                  |

## How it maps to the Redis quick-start

| Redis quick-start | Java / Hazelcast equivalent |
|---|---|
| `create_long_term_memory()` | `createLongTermMemory()` — embed + `VectorDocument.of()` + `setAsync()` |
| `search_long_term_memory()` | `searchLongTermMemory()` — embed query + `searchAsync()` |
| `put_working_memory()` | `putWorkingMemory()` — `IMap.put(sessionId, messages)` |
| `memory_prompt()` | `memoryPrompt()` — only piece you write yourself; Redis does it server-side |
| Chat completion | `callOllamaChat()` — POST to `localhost:11434/api/chat` |

### What Hazelcast doesn't provide vs Redis agent-memory-server

The Redis project is a full application on top of Redis. Hazelcast provides the storage layer only. The following features exist in Redis but must be built manually if needed:

| Feature | Redis | Hazelcast |
|---|---|---|
| Relevance threshold | Server-side | `MIN_RELEVANCE` constant in client |
| userId scoping | Server-side index | Post-filter in `searchLongTermMemory()` |
| Hybrid search (vector + BM25) | Built-in | Not available |
| Auto-summarisation | Built-in | Build yourself |
| Working → long-term promotion | Built-in | Build yourself |
| Deduplication | Built-in | Build yourself |
| MCP server interface | Built-in | Not available |

---

## Prerequisites

| Requirement | Details |
|---|---|
| Java | 25 (Vector API is standard — no `--add-modules` flag needed) |
| Hazelcast Enterprise license | With "Advanced AI" feature — [free 30-day trial](https://hazelcast.com/get-started/) |
| Ollama | Running on `localhost:11434` |

---

## Setup

### Install Ollama and pull models

```bash
# macOS: download from https://ollama.com/download (use the .app, not Homebrew)
# Linux:
curl -fsSL https://ollama.com/install.sh | sh

ollama pull nomic-embed-text
ollama pull llama3.2
```

