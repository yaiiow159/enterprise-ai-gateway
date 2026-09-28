# Enterprise AI Gateway

OpenAI 相容的 LLM 網關：多 Provider 路由、延遲對沖、失敗切換、串流轉發、檢索增強，
以及會真的攔截的成本上限。Spring Boot 3.5 + Java 21 虛擬執行緒。

現有的 SDK 只要改 base URL、換成網關核發的 API key 就能接進來。

```bash
curl http://localhost:8090/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $GATEWAY_API_KEY" \
  -d '{"model":"fake-chat","messages":[{"role":"user","content":"hi"}],"max_tokens":50,"stream":true}'
```

---

## 快速開始

需要 Docker、JDK 21、Node 18+、Maven。

```bash
node .claude/skills/dev-stack/up.js
```

```bash
mvn clean install -DskipTests
```

先產生一把 key（至少 24 字元），放進環境變數 `GATEWAY_API_KEY`：

```bash
openssl rand -hex 24
```

```bash
GATEWAY_API_KEYS="local-dev=${GATEWAY_API_KEY}" java -jar gateway-server/target/gateway-server-0.0.1-SNAPSHOT.jar
```

網關預設要求 API key，沒有設定任何 key 會**拒絕啟動**。`GATEWAY_API_KEYS` 的格式是
`租戶=key`，多筆以逗號分隔，同一個租戶可以有多把 key（輪替時新舊並存）。
租戶由 key 決定，不接受客戶端自報。只在本機開發時可以用 `GATEWAY_AUTH_ENABLED=false` 停用，
那時租戶改由 `X-Tenant` 標頭自報。

`tools/` 與 `.claude/skills/` 裡的腳本從 `GATEWAY_API_KEY` 讀要送的 key；
會自己啟動網關的腳本（`release-check`、`bench`、`verify-*`）則每次產生一把臨時 key。

請求裡的 `model` 必須是某個 Provider 在 `served-models` 宣告的名稱，否則回 404。
本機設定的三個假 Provider 共同服務 `fake-chat`。

| 服務 | 位址 |
| --- | --- |
| 網關 | http://localhost:8090 |
| Grafana | http://localhost:3003（admin/admin，儀表板自動載入）|
| Prometheus | http://localhost:9091 |
| Redis | localhost:6380（跨節點成本帳本）|
| Qdrant | http://localhost:6333（RAG 向量庫）|
| 假 Provider | 9101 / 9102 / 9103（快 / 慢 / 不穩）|

連接埠可用環境變數覆寫（`REDIS_PORT`、`GRAFANA_PORT` 等），預設值避開常見佔用。

---

## 檢索增強（RAG）

先建索引，再用 `gateway.rag.enabled=true` 啟用：

```bash
node tools/rag-index.js --input docs/rag-sample-kb.jsonl --collection docs
```

在請求裡帶 `x_rag` 啟用檢索（OpenAI SDK 用 `extra_body` 傳）：

```json
{
  "model": "fake-chat",
  "messages": [{"role": "user", "content": "token bucket rate limit"}],
  "max_tokens": 200,
  "x_rag": {"collection": "docs", "top_k": 3, "degrade_on_failure": false}
}
```

回應的 `x_gateway.rag` 會帶回檢索到的來源、分數、嵌入成本，以及有沒有降級。

啟動時會比對設定與索引的嵌入模型／維度，**不符就拒絕啟動**——
維度相同但模型不同時檢索不會報錯，只會回傳一堆不相關的結果。

---

## 架構

```
客戶端 ──► ChatController ──► RagAugmenter（檢索並注入 context）
                                    │
                                    ▼
                            HedgingChatClient ──► BudgetLedger（送出前預扣）
                                    │
                                    ├─ 延遲對沖 / 失敗切換 / 半開探測
                                    ▼
                            OpenAiCompatibleProvider ──► 上游
```

| 模組 | 職責 |
| --- | --- |
| `gateway-core` | Provider 抽象、路由、對沖、成本帳本、檢索、串流原語。**不依賴 Spring** |
| `gateway-providers` | OpenAI 相容端點與 Qdrant 的實作 |
| `gateway-server` | HTTP 端點、設定、指標 |

主要設定在 [application.yml](gateway-server/src/main/resources/application.yml)：
路由策略、對沖策略、成本上限（`memory` / `redis` 後端）、RAG。

技術決定與其理由寫在 [CLAUDE.md](CLAUDE.md)。

---

## 開發工具

被測系統的下游要花錢、會抖動、不能在 CI 裡呼叫，所以有一整套替身與驗證工具。

| 工具 | 用途 |
| --- | --- |
| `dev-stack` | 起基礎設施並等到真的可用 |
| `fake-provider` | 可控的假 LLM：TTFT、錯誤碼、中途斷線、嵌入都能指定 |
| `stream-check` | SSE 的緩衝、取消、token 完整性、中斷表達 |
| `cost-guard` | 金鑰／`max_tokens`／對沖策略掃描 + 成本倍率量測 |
| `bench` | TTFT 分位數與虛擬／平台執行緒對照 |
| `rag-eval` | 嵌入一致性檢查與 recall@k |
| `release-check` | 串成一次交付把關，可接進 CI |

兩個 hook：`secret-guard`（`PreToolUse`，偵測到金鑰就**阻擋寫入**）、
`cost-scan`（`PostToolUse`，只在阻擋級發現時提醒）。

```bash
node .claude/skills/release-check/check.js
```

---

## 實測數字

環境 i9-12900H × 20 / 64GB / JDK 21.0.3 / Windows，並行 100 條串流、
上游首字延遲 800ms、平台執行緒池上限 20：

| 組別 | TTFT p50 | TTFT p95 | tokens/s |
| --- | --- | --- | --- |
| `direct`（基準線，不經網關）| 858ms | 888ms | 1709 |
| `virtual`（虛擬執行緒）| 1066ms | 1215ms | 1480 |
| `platform`（平台執行緒，池上限 20）| 2430ms | 4062ms | 465 |

```bash
node .claude/skills/bench/bench.js --concurrency 100 --rounds 3 --upstream-ttft 800 --platform-threads 20
```

平台執行緒的差距（相對基準線 +3150ms 左右）每次執行都看得到；
網關本身的額外延遲落在量測解析度邊緣，**引用時要連同報告裡的解析度一起看**。

多節點成本上限（兩節點共用同一個上限）：`memory` 後端總支出是上限的 1.95 倍
（各記各的），`redis` 後端 0.97 倍。

---

## 目前還沒有的東西

- 網關只轉送工具呼叫，不執行工具；非串流請求在客戶端離線後仍會生成到結束（要能取消請用串流）
- 沒有語意快取、沒有工作流編排
- 沒有後台管理頁面（觀測用 Grafana，改設定要重啟）
- PostgreSQL 在 `dev-stack` 裡但應用未使用

其餘已知缺口記在 [CLAUDE.md](CLAUDE.md) 的「已知待釐清的設計問題」。

---

## 文件

| 文件 | 內容 |
| --- | --- |
| [CLAUDE.md](CLAUDE.md) | 技術決定、開發約定、待釐清的設計問題 |
| [docs/agent-tooling-plan.md](docs/agent-tooling-plan.md) | 工具鏈規劃與實作時抓到的缺陷 |
| `docs/benchmark/` | 壓測報告（含環境、逐輪原始數字、重現指令）|
