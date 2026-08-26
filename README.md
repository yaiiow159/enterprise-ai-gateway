# Enterprise AI Gateway

OpenAI 相容的 LLM 網關：多 Provider 路由、延遲對沖、失敗切換、串流轉發，以及**會真的攔截的成本上限**。

Spring Boot 3.5 + Java 21 虛擬執行緒。現有的 SDK 只要改 base URL 就能接進來。

```bash
curl http://localhost:8090/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"any","messages":[{"role":"user","content":"hi"}],"max_tokens":50,"stream":true}'
```

---

## 這個系統在防什麼

一般後端的 bug 是服務壞掉——會有人尖叫，會有告警。**這裡的 bug 是帳單，而帳單要到月底才尖叫。**
而且這類系統的失敗大多不會報錯：

| 失敗 | 外觀 |
| --- | --- |
| 使用者關掉頁面，上游還在生成 | 狀態碼 200，內容正確，只是我們在為沒人看的 token 付錢 |
| 對沖落敗者被記成故障 | 那家 Provider 之後再也不會被選中，沒有任何錯誤 |
| 上游全掛 | 網關回 HTTP 200 加一個錯誤事件，只看狀態碼的重試中介層當成成功 |
| 接上真 Provider 後成本記成 0 | 帳目上永遠免費，直到對帳的時候 |
| 嵌入模型換了但索引沒重建 | 檢索照常回傳最近鄰，只是全部不相關 |

上面每一項都是這個專案**實際發生過並修掉**的缺陷，不是假想。
它們也決定了整套開發工具的形狀：工具鏈的重點不是跑得快，是讓「看起來正常的失敗」現形。

---

## 快速開始

需要 Docker、JDK 21、Node 18+、Maven。

```bash
node .claude/skills/dev-stack/up.js
```

```bash
mvn clean install -DskipTests
```

```bash
java -jar gateway-server/target/gateway-server-0.0.1-SNAPSHOT.jar
```

`dev-stack` 會起 Redis、PostgreSQL、Qdrant、三個假 Provider、Prometheus、Grafana，
並**等到各服務真的可用**才回報就緒（容器 `Up` 不等於服務可用）。

| 服務 | 位址 | 備註 |
| --- | --- | --- |
| 網關 | http://localhost:8090 | |
| Grafana | http://localhost:3003 | admin/admin，儀表板自動載入 |
| Prometheus | http://localhost:9091 | |
| Redis | localhost:6380 | 跨節點成本帳本 |
| 假 Provider | 9101 / 9102 / 9103 | 快 / 慢 / 不穩，三種個性 |
| PostgreSQL | localhost:5433 | **應用目前未使用** |
| Qdrant | http://localhost:6333 | **應用目前未使用**，僅 `rag-eval` 會碰 |

連接埠都可用環境變數覆寫（`REDIS_PORT`、`GRAFANA_PORT` 等），
預設值刻意避開常見佔用（6379／9090／3000）。

---

## 架構

```
客戶端 ──► ChatController (SSE)
              │
              ▼
        HedgingChatClient ──► BudgetLedger（送出前預扣）
              │                    └─ memory / redis
              │
              ├─ 路由排序（延遲感知 + 半開探測）
              ├─ 延遲對沖（只在主要超過 p95 才補發）
              └─ 失敗切換（不受對沖預算限制）
                     │
                     ▼
              OpenAiCompatibleProvider ──► 上游（JDK HttpClient）
                     │
                     └─ close() = 中止交換 = 停止計費
```

| 模組 | 職責 |
| --- | --- |
| `gateway-core` | Provider 抽象、路由、對沖、成本帳本、串流原語。**不依賴 Spring** |
| `gateway-providers` | OpenAI 相容端點的實作 |
| `gateway-server` | HTTP 端點、設定、指標 |

---

## 幾個非顯而易見的設計決定

完整說明在 [CLAUDE.md](CLAUDE.md)，這裡只列最容易寫錯的：

**對沖與失敗切換是兩回事。** 對沖是在前一個候選**還在飛**的時候多打一家賭它比較快，
是可選的花費，受 `max-attempts` 限制。失敗切換是在飛的候選**全部失敗**之後換下一家，
不做就等於放棄多 Provider 架構，因此不受該限制。
早期版本把兩者混在同一個預算裡，結果是主要 Provider 一回 429，備援根本不會被啟動。

**串流的狀態碼要誠實，代價是標頭與第一個 token 一起送。**
先取得上游串流再建立 `SseEmitter`——一旦回傳 emitter，狀態碼就定案了。
代價是客戶端分不出「已連上、模型正在想」與「請求還沒被接受」，這是刻意接受的取捨。

**取消要真的傳到上游。** 串流以 `BodyHandlers.ofInputStream()` 取得，
關閉那個 InputStream 會中止 HTTP 交換。這一行就是「使用者離開後不再付錢」的實際機制。

**成本上限預扣而非事後記帳。** 事後記帳擋不住併發——一百個請求同時通過檢查、
同時發現額度還夠，然後一起超支。

**金額一律用整數微美分。** LLM 的單次成本常常小到 0.0001 美分，
浮點數累加數百萬筆會產生可觀誤差，而這是要拿去對帳的數字。

---

## 開發工具

被測系統的下游**要花錢、會抖動、而且不能在 CI 裡呼叫**。
這個前提決定了整套工具的優先序。

| 工具 | 用途 |
| --- | --- |
| `dev-stack` | 起基礎設施並等到真的可用 |
| `fake-provider` | 可控的假 LLM：TTFT、錯誤碼、中途斷線都能指定，並統計取消次數 |
| `stream-check` | SSE 的緩衝、取消、token 完整性、中斷表達（19 項）|
| `cost-guard` | 金鑰／`max_tokens`／對沖策略靜態掃描 + 成本倍率量測 |
| `bench` | TTFT 分位數與虛擬／平台執行緒對照，含基準線歸因 |
| `rag-eval` | 嵌入一致性契約與 recall@k |
| `release-check` | 串成一次交付把關，可接進 CI |

兩個 hook，嚴格程度刻意不同：

- **`secret-guard`**（`PreToolUse`）—— 偵測到 Provider 金鑰就**阻擋寫入**。
  金鑰進了 git 歷史就只能輪替，事後警告等於「已經發生了，請去善後」。
- **`cost-scan`**（`PostToolUse`）—— 只提醒，且只在阻擋級發現時出聲。
  成本風險常需要人判斷，機械式阻擋只會讓 hook 被關掉。

### 一個貫穿全部的原則

**沒紅過的斷言可能根本沒在測東西。** 每支工具都附靈敏度驗證，刻意製造它該抓到的缺陷，
確認它會變紅。這在開發 `bench` 時救了一次——當時的探測腳本把 25 條全部失敗的請求
報成「成功 25，失敗 0」，還從中得出了錯誤的結論。

---

## 實測數字

**這些是量測值，不是目標值。** 環境、參數與重現指令一併附上，否則數字無法被檢驗。

環境：i9-12900H × 20 / 64GB / JDK 21.0.3 / Windows。
並行 100 條串流、上游首字延遲 800ms、平台執行緒池上限 20：

| 組別 | TTFT p50 | TTFT p95 | tokens/s |
| --- | --- | --- | --- |
| `direct`（基準線，不經網關）| 858ms | 888ms | 1709 |
| `virtual`（虛擬執行緒）| 1066ms | 1215ms | 1480 |
| `platform`（平台執行緒，池上限 20）| 2430ms | 4062ms | 465 |

```bash
node .claude/skills/bench/bench.js --concurrency 100 --rounds 3 --upstream-ttft 800 --platform-threads 20
```

**兩個結論的強度差很多，這比數字本身重要：**

- **平台執行緒明顯較差**——p95 一致落在 4000ms 上下（相對基準線 +3150ms 左右），
  tokens/s 只有虛擬執行緒的約 27%。**任何一次執行都看得到**。
  這個數字與排隊模型相符：100 條並行 ÷ 20 條執行緒 = 5 批 × 800ms。
- **網關本身的額外延遲**——跨多次執行落在 +45ms ~ +327ms，而量測解析度是 ±50ms ~ ±144ms。
  **落在解析度邊緣**，有時可宣稱、有時量不出來。

所以正確的說法是「網關開銷小到不容易量出來」，不是「網關只慢 45ms」。
報告會逐輪列出原始數字與該輪的解析度，**引用數字時要連同解析度一起引用**。

多節點成本上限（`tools/verify-budget-multinode.js`，兩節點共用上限）：

| 後端 | 實際總支出 | 相對上限 |
| --- | --- | --- |
| `memory` | 34100 微美分 | **1.95 倍**（各記各的）|
| `redis` | 17050 微美分 | **0.97 倍**（共用一份帳）|

---

## 目前還沒有的東西

誠實列出，避免誤會：

- **沒有任何 JUnit 測試。** 驗證全部是端到端的 Node 腳本（`release-check` 串起來）。
  單元測試該補，但這類系統的缺陷幾乎都出在跨元件的互動上，端到端先做是刻意的取捨。
- **沒有 RAG。** Qdrant 在 `dev-stack` 裡跑著，但應用沒有接。
  `rag-eval` 已定義好「collection 必須記錄自己是用哪個嵌入模型建的」這個契約，
  等實作進來就能用。
- **沒有語意快取、沒有工作流編排。**
- **沒有後台管理頁面。** 觀測用 Grafana，改設定目前要重啟。
  執行期設定的管理 API 是下一步，UI 要等多租戶隔離層級定案。
- **PostgreSQL 沒有被使用。**

其他已知的設計缺口記在 [CLAUDE.md](CLAUDE.md) 的「已知待釐清的設計問題」，
包含 token 計數精度、多租戶隔離層級、以及延遲感知路由的冷啟動飢餓。

---

## 文件

| 文件 | 內容 |
| --- | --- |
| [CLAUDE.md](CLAUDE.md) | 技術決定、開發約定、待釐清的設計問題 |
| [docs/agent-tooling-plan.md](docs/agent-tooling-plan.md) | 工具鏈規劃，以及每個工具實作時抓到的缺陷 |
| `docs/benchmark/` | 壓測報告（含環境、逐輪原始數字、重現指令）|

`docs/agent-tooling-plan.md` 的「實作後補記」值得一讀——
那裡記的是每次量錯、判斷錯、以及**為什麼一個看起來正常的系統其實是壞的**。
