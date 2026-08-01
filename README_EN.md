English | [中文](README.md)

# ERP AI Assistant — Spring AI RAG Demo

A manufacturing ERP AI assistant built with Spring AI, integrating **Tool Calling** (real-time business data queries) and **RAG** (retrieval over user-imported knowledge documents). Supports multi-model switching, streaming chat, conversation memory, multi-tenant isolation, billing management, dynamic Tool management, Tool call tracing, and business data chart visualization.

![Screenshot](doc/img.png)

## Tech Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| Language | Java | 17+ |
| Framework | Spring Boot | 4.0.7 |
| AI Framework | Spring AI | 2.0.0 |
| Chat Models | DeepSeek / Qwen (Tongyi) / Google Gemini | Multi-model switchable |
| Embedding Model | all-MiniLM-L6-v2 (ONNX local inference) | 384-dim vectors |
| Vector Database | PostgreSQL + PgVector | HNSW index |
| Business Database | MySQL | ERP business data |
| Data Access | Spring JDBC + MyBatis-Plus | 3.5.16 |
| Frontend | Vanilla HTML/CSS/JS + marked.js + highlight.js + Apache ECharts | SPA |
| Streaming | SSE (Server-Sent Events) | WebFlux Reactor |

### Key Features

- **Spring AI Tool Calling** — LLM automatically invokes code `@Tool` methods across 8 ERP modules and database-defined dynamic Tools to query MySQL in real time
- **Tool Result Chart Visualization** — The LLM selects only the chart type and title; the backend builds a safe, generic chart specification from structured business data returned in the current turn, and the frontend renders it with local ECharts assets
- **Spring AI RAG** — `QuestionAnswerAdvisor` retrieves snippets from user-imported knowledge documents in PgVector and injects them into the prompt context
- **Multi-Model Switching** — Frontend dropdown selects a model; backend routes to the corresponding provider's `ChatModel` via `ModelRegistry`
- **Conversation Memory** — `MessageChatMemoryAdvisor` + `JdbcChatMemoryRepository` for database-backed context memory
- **Multi-Tenant Isolation** — All data queries and vector searches are automatically filtered by `ent_code`
- **Streaming Markdown Rendering** — SSE token-by-token push + real-time marked.js rendering (tables, bold, lists, etc.)
- **Dynamic Tool Management** — Maintain SQL query Tools from the frontend and refresh the runtime Tool snapshot after changes
- **Tool Call Tracing** — Persist each Tool call with tenant, conversation, model, arguments, status, duration, and result size
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

### 2. MySQL (ERP Business Data + Chat History + Billing + Dynamic Tools)

```bash
docker run -d \
  --name mysql-erp \
  -p 13306:3306 \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=erp \
  -e MYSQL_CHARSET=utf8mb4 \
  mysql:8.0
```

The application initializes ERP business tables (`b_sales_order`, `b_purchase_order`, etc.) and platform tables (`a_chat_conversation`, `a_chat_message`, `a_billing_account`, `a_llm_tool`, `a_tool_call_log`, etc.) on startup, skipping existing seed rows by business keys.

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
docker build -t ly753/spring-ai-rag-demo:latest .
```

To push the current image to the remote repository:

```bash
docker login
docker push ly753/spring-ai-rag-demo:latest
```

The build entry is in the project root, and the Maven mirror configuration is in the `deploy/` directory:

```
Dockerfile             # Multi-stage build: Maven compile + JRE runtime
deploy/
└── settings.xml       # Aliyun Maven mirror for faster builds in China
```

### Deploy with docker-compose

```bash
# Start all services (PgVector + MySQL + Application)
docker-compose up -d
```

`docker-compose.yml` uses the remote image `ly753/spring-ai-rag-demo:latest` for the application container and starts PgVector and MySQL. MySQL schema and demo data are initialized idempotently by the application on startup; LLM chat still requires at least one real model API key via environment variables.

## Features

### AI Chat

| Feature | Description |
|---------|-------------|
| Auto Mode | Tool Calling + RAG enabled simultaneously; LLM decides which to use |
| Data Query Mode | Tool Calling only; best for explicit business data queries |
| Knowledge Mode | RAG only; answers from user-imported knowledge documents without ERP domain restrictions |
| Streaming Output | SSE token-by-token push with real-time Markdown rendering |
| Chart Display | When a Tool returns visualizable business data in the current turn, at most one chart is appended to that assistant response; later responses in the same conversation may generate additional charts |
| Conversation Memory | Database-backed context memory (20-message sliding window) |
| Multi-Model Switching | Switch between DeepSeek / Qwen / Gemini from a frontend dropdown |
| Preset Hints | AI-generated sample questions based on Tool descriptions |
| Message Copy | One-click copy on all message bubbles |

### Tool Result Chart Visualization

When Auto Mode or Data Query Mode invokes a business Tool and the current result is suitable for visualization, the system appends one chart after the Markdown business answer:

- The LLM selects only the chart type and business title. It never supplies field bindings, business values, or raw ECharts options.
- The backend selects a source from the structured Tool results captured in the current turn, binds fields, applies controlled transformations, and produces a versioned `ChartSpec`.
- Each assistant response contains at most one chart. A conversation may still contain charts in multiple turns.
- Non-streaming responses expose an optional `chart` field; streaming responses use typed `delta`, `chart`, `done`, and `error` SSE events.
- The chart is persisted with the assistant message and replayed from chat history without rerunning the business query or calling the LLM again.
- Planning, compilation, or rendering failures gracefully fall back to Markdown text without interrupting the business answer.
- The frontend uses local Apache ECharts assets plus word-cloud and liquid-fill extensions, with no CDN dependency.

The chart protocol supports 23 types: donut, sunburst, bar, waterfall, bullet, area, step, radar, scatter, bubble, histogram, boxplot, heatmap, Sankey, treemap, Gantt, funnel, word cloud, gauge, liquid fill, parallel coordinates, line, and pie. Sunburst, Sankey, and treemap require Tool results with suitable hierarchy or link relationships; the other 20 types can be tested directly with the seeded demo data below.

#### Screenshots

<table>
  <tr>
    <td align="center"><strong>Bar</strong><br><img src="doc/chart-examples/01-bar.png" alt="Product sales quantity bar chart" width="100%"></td>
    <td align="center"><strong>Waterfall</strong><br><img src="doc/chart-examples/02-waterfall.png" alt="Outsourcing material quantity waterfall chart" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>Bullet</strong><br><img src="doc/chart-examples/03-bullet.png" alt="Ordered versus received quantity bullet chart" width="100%"></td>
    <td align="center"><strong>Area</strong><br><img src="doc/chart-examples/04-area.png" alt="Sales amount trend area chart" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>Step</strong><br><img src="doc/chart-examples/05-step.png" alt="Inventory movement quantity step chart" width="100%"></td>
    <td align="center"><strong>Gantt</strong><br><img src="doc/chart-examples/06-gantt.png" alt="Production work order schedule Gantt chart" width="100%"></td>
  </tr>
</table>

#### Copy-Paste Chart Test Prompts

These prompts use the project's seeded demo data. Run them in Auto Mode or Data Query Mode. When a prompt explicitly requests a supported chart type, the LLM is instructed to prioritize that type.

| No. | Chart | Copy-Paste Test Prompt |
|---:|---|---|
| 1 | Donut | Query after-sales tickets from March 1 to March 31, 2026, count tickets by processing status, and use a donut chart to show each status share. |
| 2 | Bar | Query sales orders from March 1 to March 31, 2026, aggregate sales quantity by product, and use a bar chart to show product sales. |
| 3 | Waterfall | Query the incoming, returned, and rejected material records for outsourcing order OO20260301, show quantity changes by product, and display them in a waterfall chart. |
| 4 | Bullet | Query the receipt status of purchase order PO20260302, and use a bullet chart to compare the ordered and received quantities of the NTC10K temperature sensor. |
| 5 | Area | Query sales orders from March 1 to March 31, 2026, show sales amount changes by order date, and use an area chart to display the sales trend. |
| 6 | Step | Query inventory movement records from March 1 to March 31, 2026, show inbound and outbound quantity changes by date, and display them in a step chart. |
| 7 | Radar | Query the quality inspection results for batch L20260309, and use a radar chart to compare sampled, qualified, and defective quantities. |
| 8 | Scatter | Query sales orders from March 1 to March 31, 2026, and use a scatter plot to analyze the relationship between order quantity and sales amount. |
| 9 | Bubble | Query quality inspection records from March 1 to March 31, 2026, use a bubble chart to show the relationship between qualified and defective quantities, and use sampled quantity as bubble size. |
| 10 | Histogram | Query sales orders from March 1 to March 31, 2026, and use a histogram to show the distribution of order sales amounts. |
| 11 | Boxplot | Query sales orders from March 1 to March 31, 2026, and use a boxplot to show the order amount distribution by customer. |
| 12 | Heatmap | Query sales orders from March 1 to March 31, 2026, and use a heatmap to show sales quantities for each customer-product combination. |
| 13 | Gantt | Query production work orders starting from March 1 to March 31, 2026, and use a Gantt chart to show each product work order's planned start and end dates. |
| 14 | Funnel | Query after-sales tickets from March 1 to March 31, 2026, count them by processing status, and use a funnel chart to show the ticket status distribution. |
| 15 | Word Cloud | Query after-sales tickets from March 1 to March 31, 2026, count occurrences by issue type, and use a word cloud to show issue frequencies. |
| 16 | Gauge | Query the accounts receivable of customer `张三电子科技有限公司`, and use a gauge chart to show the current outstanding receivable balance. |
| 17 | Liquid Fill | Query the sales summary from March 1 to March 31, 2026, and use a liquid-fill chart to show the customer count. |
| 18 | Parallel Coordinates | Query production work orders starting from March 1 to March 31, 2026, and use a parallel coordinates chart to compare planned, completed, and scrapped quantities for each product work order. |
| 19 | Line | Query payment receipt records from March 1 to March 31, 2026, and use a line chart to show the payment amount trend by receipt date. |
| 20 | Pie | Query sales orders from March 1 to March 31, 2026, aggregate sales quantity by product, and use a pie chart to show each product's sales share. |

### ERP Tool Calling (Code Tools + Dynamic Database Tools)

The system merges existing code `@Tool` objects under `com.example.rag.tool` with enabled database-defined dynamic Tools into one Spring AI Tool snapshot. auto/data modes can call Tools; knowledge mode uses RAG only and does not expose Tools.

#### Code ERP Tools (8 Modules, 39 Tool Methods)

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

#### Dynamic LLM Tool Management

| Feature | Description |
|---------|-------------|
| Database definitions | `a_llm_tool` globally stores Tool name, description, input JSON Schema, SQL template, main table alias, result limit, and status |
| Frontend management | The top-level Tool Management page supports create, edit, delete, enable/disable, refresh, and Tool call log viewing |
| Runtime refresh | Saving, deleting, or changing status refreshes `ToolSnapshot`; subsequent LLM chats use the latest Tools |
| Tenant isolation | Dynamic Tool definitions are global, but execution always reads the current `ent_code` and injects it into SQL conditions |
| SQL safety | The first version only allows single-level read-only `SELECT`, uses bind variables, and rejects writes, DDL, multi-statements, complex query blocks, and unsafe aliases |
| Call logs | `a_tool_call_log` records Tool source, arguments, status, duration, result count, error summary, and assistant message linkage |
| Frontend display | Status and source are shown in Chinese labels while API values remain English enums: `active`/`inactive`, `code`/`database` |
| Schema assistance | The input Schema editor provides JSON Schema guidance, parameter naming rules, and editable sample data |
| Seed examples | Default demo data includes `query_dynamic_sales_orders` and `query_inventory_lot_location` |

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

`ModelRegistry.PROVIDER_BEAN_NAMES` already includes the enabled providers and the Spring AI 2.0.0 GA mappings confirmed for `anthropic`, `ollama`, `mistral`, and `bedrock`. Add an entry only when the new provider is still not mapped:

```java
private static final Map<String, String> PROVIDER_BEAN_NAMES = Map.ofEntries(
    Map.entry("deepseek",     "deepSeekChatModel"),
    Map.entry("openai",       "openAiChatModel"),
    Map.entry("google-genai", "googleGenAiChatModel"),
    // Example: Map.entry("custom-provider", "customProviderChatModel")
);
```

Bean names come from each Spring AI starter's AutoConfiguration class. Historical providers such as `azure-openai`, `vertex-ai-gemini`, `minimax`, `zhipu`, `huggingface`, and `oci-genai` are not confirmed against Spring AI 2.0.0 GA; verify the starter coordinates and ChatModel bean names before enabling them later.

## Project Structure

```
com.example.rag
├── RagDemoApplication              # Application entry point
├── chat/                           # AI chat core
│   ├── ErpAssistantService         # LLM chat orchestration + Tool Calling + RAG
│   ├── DocumentLoaderService       # Document ingestion into vector store
│   ├── ModelRegistry               # Multi-model registry
│   ├── client/                     # Provider routing, ChatClient caching, and Tool assembly
│   ├── lifecycle/                  # Message, billing, Tool log, and answer finalization
│   ├── dto/                        # Internal chat DTOs and records
│   ├── output/                     # Assistant answer sanitization and narration filtering
│   └── chart/                      # Tool result chart visualization
│       ├── capture/                # Structured business Tool results captured for the current turn
│       ├── compile/                # Field binding, transformations, planning, and trusted compilation
│       ├── model/                  # Internal chart models
│       ├── protocol/               # ChartSpec codec and protocol validation
│       ├── selection/              # LLM chart type and title selection
│       └── tool/                   # Internal chart selection Tool
├── controller/                     # HTTP controller entry points
│   ├── ChatController              # Chat / docs / models / hints
│   ├── ConversationController      # History API
│   ├── BillingController / BillingManagementController
│   ├── TenantManagementController
│   └── ToolManagementController    # Dynamic Tool management and call log API
├── conversation/                   # Chat history persistence
│   ├── ChatHistoryService          # Conversation / message CRUD
│   └── JdbcChatMemoryRepository    # Spring AI ChatMemory backed by JDBC
├── billing/                        # Billing
│   ├── BillingManagementService
│   └── BillingService
├── tool/                           # ERP Tool Calling (8 modules)
│   ├── BaseTool                    # Tenant isolation base class (auto-injects ent_code)
│   ├── SalesTool / PurchaseTool / ProductionTool / QualityTool
│   ├── WarehouseTool / FinanceTool / AfterSalesTool / OutsourcingTool
│   ├── admin/                      # Dynamic Tool management service
│   ├── dynamic/                    # SQL validation, binding, tenant injection, and execution
│   ├── registry/                   # Tool snapshot registration and refresh
│   └── trace/                      # Tool call logs and per-request aggregation
├── dao/                            # MyBatis-Plus Entity / Mapper
│   ├── entity/                     # Chat, billing, tenant, Tool definition, and Tool call log entities
│   └── mapper/                     # ERP MySQL data access mappers
├── init/                           # MySQL schema and demo data initialization
├── config/                         # Configuration
│   ├── ModelProperties             # Multi-model YAML config binding
│   ├── ChatModelConfig             # @Primary ChatModel declaration
│   ├── DataSourceConfig            # Dual datasource (PgVector + MySQL)
│   ├── TenantFilter / TenantContext / TenantContextAccessor
│   ├── ContextPropagationConfig    # Reactor context propagation
│   └── GlobalExceptionHandler
└── vo/                             # Request / response objects
    ├── ChatVO / ConversationVO / BillingVO / AdminVO / RespVO
    └── ChartVO                     # Versioned chart protocol shared by backend and frontend
```
