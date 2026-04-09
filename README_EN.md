English | [中文](README.md)

# ERP AI Assistant — Spring AI RAG Demo

A manufacturing ERP AI assistant built with Spring AI, integrating **Tool Calling** (real-time business data queries) and **RAG** (product manual knowledge retrieval). Supports multi-model switching, streaming chat, conversation memory, multi-tenant isolation, and billing management.

![Screenshot](doc/img.png)

## Tech Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| Language | Java | 17+ |
| Framework | Spring Boot | 3.5.12 |
| AI Framework | Spring AI | 1.1.4 |
| Chat Models | DeepSeek / Qwen (Tongyi) / Google Gemini | Multi-model switchable |
| Embedding Model | all-MiniLM-L6-v2 (ONNX local inference) | 384-dim vectors |
| Vector Database | PostgreSQL + PgVector | HNSW index |
| Business Database | MySQL | ERP business data |
| Frontend | Vanilla HTML/CSS/JS + marked.js + highlight.js | SPA |
| Streaming | SSE (Server-Sent Events) | WebFlux Reactor |

### Key Features

- **Spring AI Tool Calling** — LLM automatically invokes `@Tool` methods across 8 ERP modules to query MySQL in real time
- **Spring AI RAG** — `QuestionAnswerAdvisor` retrieves product manual snippets from PgVector and injects them into the prompt context
- **Multi-Model Switching** — Frontend dropdown selects a model; backend routes to the corresponding provider's `ChatModel` via `ModelRegistry`
- **Conversation Memory** — `MessageChatMemoryAdvisor` + `JdbcChatMemoryRepository` for database-backed context memory
- **Multi-Tenant Isolation** — All data queries and vector searches are automatically filtered by `ent_code`
- **Streaming Markdown Rendering** — SSE token-by-token push + real-time marked.js rendering (tables, bold, lists, etc.)
- **Billing Management** — Token-based billing with plan quotas, recharging, and transaction history

## Middleware Dependencies

### 1. PostgreSQL + PgVector (Vector Database)

```bash
docker run -d \
  --name pgvector \
  -p 5432:5432 \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=rag_demo \
  pgvector/pgvector:pg16
```

PgVector tables and indexes are auto-created on startup (`initialize-schema: true`).

### 2. MySQL (ERP Business Data + Chat History + Billing)

```bash
docker run -d \
  --name mysql-erp \
  -p 13306:3306 \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=erp \
  -e MYSQL_CHARSET=utf8mb4 \
  mysql:8.0
```

You need to import the ERP business tables (`b_sales_order`, `b_purchase_order`, etc.) and platform tables (`a_chat_conversation`, `a_chat_message`, `a_billing_account`, etc.) manually.

## Quick Start

### 1. Start Middleware

```bash
docker start pgvector
docker start mysql-erp
```

### 2. Set Environment Variables

```bash
# Required: at least one model API key
export DEEPSEEK_API_KEY=sk-your-deepseek-key

# Optional: Qwen (Tongyi Qianwen)
export DASHSCOPE_API_KEY=sk-your-dashscope-key

# Optional: Google Gemini
export GOOGLE_GENAI_API_KEY=your-google-genai-key
```

Get API keys from:
- DeepSeek: https://platform.deepseek.com/api_keys
- Qwen (DashScope): https://bailian.console.aliyun.com/cn-beijing?tab=model#/api-key
- Gemini: https://aistudio.google.com/app/apikey

### 3. Run the Application

```bash
mvn clean spring-boot:run
```

Open http://localhost:8080 in your browser.

## Docker Deployment

### Use Pre-built Image (Recommended)

```bash
docker pull ly753/spring-ai-rag-demo:latest

docker run -d --name rag-demo \
  --network host \
  -e DEEPSEEK_API_KEY=sk-your-deepseek-key \
  -e DASHSCOPE_API_KEY=sk-your-dashscope-key \
  -e GOOGLE_GENAI_API_KEY=your-google-genai-key \
  -e ERP_DB_PASSWORD=your-mysql-password \
  ly753/spring-ai-rag-demo:latest
```

> PgVector and MySQL must be running first (see "Middleware Dependencies" above).

### Build Image Locally

```bash
# Run from project root
docker build -f deploy/Dockerfile -t ly753/spring-ai-rag-demo:latest .
```

Build-related files are located in the `deploy/` directory:

```
deploy/
├── Dockerfile         # Multi-stage build: Maven compile + JRE runtime
└── settings.xml       # Aliyun Maven mirror for faster builds in China
```

### Deploy with docker-compose

```bash
# Start all services (PgVector + MySQL + Application)
docker-compose up -d
```

## Features

### AI Chat

| Feature | Description |
|---------|-------------|
| Auto Mode | Tool Calling + RAG enabled simultaneously; LLM decides which to use |
| Data Query Mode | Tool Calling only; best for explicit business data queries |
| Knowledge Mode | RAG only; best for product manual Q&A |
| Streaming Output | SSE token-by-token push with real-time Markdown rendering |
| Conversation Memory | Database-backed context memory (20-message sliding window) |
| Multi-Model Switching | Switch between DeepSeek / Qwen / Gemini from a frontend dropdown |
| Preset Hints | AI-generated sample questions based on Tool descriptions |
| Message Copy | One-click copy on all message bubbles |

### ERP Tool Calling (8 Modules, 39 Tool Methods)

| Module | Tools | Supported Queries |
|--------|-------|-------------------|
| Sales | 6 | Orders / Details / Shipments / Receivables / Summary + Time Range |
| Purchasing | 5 | Orders / Details / Receipts / Payables + Time Range |
| Production | 5 | Work Order Status / List / Materials / Routing + Time Range |
| Quality | 5 | Inspections / List / Defect Details / Pass Rate + Time Range |
| Warehouse | 5 | Inventory / Stock Details / Movements / Alerts + Time Range |
| Finance | 5 | AR Aging / AP Aging / Monthly Summary / Payments / Ledger |
| After-Sales | 4 | Tickets / Details / Returns + Time Range |
| Outsourcing | 4 | Orders / Details / Material Flow + Time Range |

All tool methods support natural language time expressions ("last week", "this month", "this year", etc.); the LLM automatically converts them to date ranges.

### Document Management

- One-click loading of preset documents from `classpath:docs/`
- Upload PDF / Word / Excel / TXT (parsed via Apache Tika)
- Document search (vector similarity retrieval for debugging)

### Billing Management

- Account balance / plan quotas
- Transaction history (recharge / deduction / gifts)
- Daily / monthly token usage statistics
- Online recharging

### Chat History

- Conversation list (paginated, sorted by update time)
- Message details (including token usage and response time metadata)
- Conversation deletion (soft delete)

## Multi-Model Configuration

The project supports multiple model providers simultaneously. Frontend users can switch freely. Configuration takes three steps:

### Step 1: Add Maven Dependencies

Each provider corresponds to a Spring AI starter:

```xml
<!-- DeepSeek (built-in) -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-deepseek</artifactId>
</dependency>

<!-- Qwen via OpenAI-compatible protocol -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>

<!-- Google Gemini -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-google-genai</artifactId>
</dependency>
```

### Step 2: Configure application.yml

```yaml
app:
  models:
    - id: deepseek-chat         # Unique ID used by frontend
      label: DeepSeek Chat      # Display name in dropdown
      provider: deepseek         # Provider identifier
      model-name: deepseek-chat  # Actual model name sent to API
      default: true              # Default selected
    - id: qwen-max
      label: Qwen Max
      provider: openai           # Qwen uses OpenAI-compatible protocol
      model-name: qwen-max

spring:
  ai:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}
    openai:
      api-key: ${DASHSCOPE_API_KEY}
      base-url: https://dashscope.aliyuncs.com/compatible-mode  # Do NOT append /v1
    google:
      genai:
        api-key: ${GOOGLE_GENAI_API_KEY}
```

### Step 3: Register Provider (Only for New Providers)

If the new provider is not already mapped, add it to `ModelRegistry.PROVIDER_BEAN_NAMES`:

```java
private static final Map<String, String> PROVIDER_BEAN_NAMES = Map.ofEntries(
    Map.entry("deepseek",     "deepSeekChatModel"),
    Map.entry("openai",       "openAiChatModel"),
    Map.entry("google-genai", "googleGenAiChatModel")
    // Add: Map.entry("anthropic", "anthropicChatModel")
);
```

Bean names come from each Spring AI starter's AutoConfiguration class.

## Project Structure

```
com.example.rag
├── RagDemoApplication              # Application entry point
├── chat/                           # AI chat core
│   ├── ChatController              # HTTP API (chat / docs / models / hints)
│   ├── ErpAssistantService         # LLM chat + Tool Calling + RAG + multi-model routing
│   ├── DocumentLoaderService       # Document ingestion into vector store
│   └── ModelRegistry               # Multi-model registry (provider → ChatClient cache)
├── conversation/                   # Chat history persistence
│   ├── ConversationController      # History API
│   ├── ChatHistoryService          # Conversation / message CRUD
│   └── JdbcChatMemoryRepository    # Spring AI ChatMemory backed by JDBC
├── billing/                        # Billing
│   ├── BillingController
│   └── BillingService
├── tool/                           # ERP Tool Calling (8 modules)
│   ├── BaseTool                    # Tenant isolation base class (auto-injects ent_code)
│   ├── SalesTool / PurchaseTool / ProductionTool / QualityTool
│   ├── WarehouseTool / FinanceTool / AfterSalesTool / OutsourcingTool
├── config/                         # Configuration
│   ├── ModelProperties             # Multi-model YAML config binding
│   ├── ChatModelConfig             # @Primary ChatModel declaration
│   ├── DataSourceConfig            # Dual datasource (PgVector + MySQL)
│   ├── TenantFilter / TenantContext / TenantContextAccessor
│   ├── ContextPropagationConfig    # Reactor context propagation
│   └── GlobalExceptionHandler
└── vo/                             # Request / response objects
    ├── ChatVO / ConversationVO / BillingVO / RespVO
```
