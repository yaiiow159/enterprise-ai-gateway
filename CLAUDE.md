# Enterprise AI Gateway — LLM 網關與工作流編排

企業級的 LLM 中台：統一多個 Provider（OpenAI／Anthropic／本地 Ollama）的介面，
依延遲、成本與能力動態路由，端到端串流輸出，並在延遲尖峰時以對沖請求（hedging）壓低長尾。

**目前狀態**：規劃階段，尚無程式碼。本文件是實作依據，不是既成事實的描述；
完成的項目請直接更新本文件，不要另外開一份「進度」文件。

---

## 這個系統要解決什麼

企業導入 LLM 時，直接讓每個服務各自呼叫 Provider SDK 會產生四個問題：

1. **供應商綁定**：換模型要改每一個呼叫端。
2. **成本失控**：沒有人知道哪個團隊、哪個功能燒掉多少錢，也擋不住失控的重試。
3. **長尾延遲**：LLM 的 p99 常是 p50 的十倍以上，單一 Provider 抖動就拖垮使用者體驗。
4. **金鑰四散**：每個服務都拿到 Provider 金鑰，輪替與稽核都做不到。

網關把這四件事收斂到一個地方。

---

## 關鍵技術決定

以下幾個決定會影響整個架構，實作前必須先看懂，不要照著常見範例抄。

### 1. Spring MVC + 虛擬執行緒，不用 WebFlux

原始構想寫「WebFlux / Reactor **或** SSE」，但同時又要用 Structured Concurrency——
這兩者方向相反：

- **WebFlux** 要求整條路徑非阻塞，一旦有人在 Reactor 執行緒上呼叫阻塞 API 就會拖垮整個 event loop。
- **Structured Concurrency** 是阻塞風格的 API，設計上就是給虛擬執行緒用的。

兩個都要，最後會寫出 `Mono.fromCallable(...).subscribeOn(boundedElastic)` 這種把
reactive 當執行緒池用的程式碼——複雜度全付了，好處一個都沒拿到。

**選擇：Spring MVC + 虛擬執行緒 + SSE。** LLM 網關幾乎全是 I/O 等待，
虛擬執行緒足以承接高併發，而且程式碼是直線的、堆疊追蹤是完整的、除錯是正常的。
WebFlux 真正的優勢在跨層背壓（backpressure），但我們的下游是 HTTP 串流，
背壓靠的是 TCP 與消費速率，Reactor 那層並沒有多給什麼。

### 2. Structured Concurrency 在 JDK 21 是 preview，正式路徑不要依賴它

**虛擬執行緒（JEP 444）在 21 是正式功能，Structured Concurrency 不是。**
它從 JDK 21 到 25 一直維持 preview，而且 API 每一版都在改
（`ShutdownOnSuccess` 在較新版本已被 `StructuredTaskScope.open(Joiner...)` 取代）。
用它就得掛 `--enable-preview`，等於把產出綁死在特定 JDK 版本，升版還要改程式碼。

**對沖請求用 `ExecutorService.invokeAny`**——語意完全相同（取第一個成功的結果、
自動取消其餘任務），而且是穩定 API：

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    return executor.invokeAny(candidates, timeout, MILLISECONDS);
}
```

想展示 Structured Concurrency 可以另外做一個以 profile 開關的實作，
並在 README 說清楚它是 preview——**當成賣點展示可以，當成正式路徑不行**。

### 3. 對沖請求不能無條件發，否則成本直接三倍

「同時請求 3 個模型取最快」聽起來很漂亮，但每一次請求都付三份錢。
LLM 的 input token 在請求送出當下就會被計費，**取消請求不會退還已產生的費用**，
只能減少後續 output token。

**採用延遲對沖（deferred hedging）**：先送主要 Provider，只有在超過該 Provider 的
p95 延遲仍未回應時，才補發給備援。長尾延遲的改善幾乎一樣，成本增加約 5% 而非 200%。

對沖必須可以按路由規則關閉，且要有每日成本上限作為最後防線。

### 4. 串流與對沖必須在第一個 token 前分出勝負

一旦開始把 A 的 token 串給客戶端，就不能改用 B 的結果——輸出會前後矛盾。
所以對沖的勝負判定點是 **TTFT（首個 token 抵達）**：誰先吐出第一個 token 就用誰，
其餘立刻取消。這也代表對沖的判斷依據是 TTFT 而非總完成時間。

### 5. Provider 抽象要以「能力」而非「型號」為單位

路由要看的是：上下文長度、是否支援 function calling／視覺輸入、串流、
每千 token 成本、目前的滾動延遲與錯誤率。把這些收斂成 `ModelCapability`，
路由策略才寫得出來，也才能在新模型上線時只加一筆設定而不改程式碼。

### 6. 串流回應的狀態碼要誠實，代價是標頭與第一個 token 一起送

**先取得上游串流，再建立 `SseEmitter`**，順序不可以顛倒。一旦把 emitter 回傳給 Spring，
HTTP 狀態碼就送出去了；那之後才發現上游失敗，就只能補一個錯誤事件，而狀態碼已經是 200。
對只檢查狀態碼的客戶端、監控與重試中介層來說，那是一次「成功」——上游三家全掛也一樣。
這是實測抓到的：三家假 Provider 全設成回 503，網關回的是 `HTTP 200`。

代價是回應標頭會等到第一個 token 才送出，客戶端因此分不出
「已連上、模型正在想」與「請求還沒被接受」。這是刻意接受的：
由決定 4，對沖要以首個 token 決勝負，所以在第一個 token 之前，
網關根本還不知道會由誰服務、甚至不知道有沒有人能服務——
在那個時間點送出的任何狀態碼都只是猜測。

**要留意的副作用**：TTFT 很長時，中間的反向代理可能因為遲遲沒有位元組而先逾時。
真的遇到再考慮送 SSE 註解行（`: ping`）保活，但那等於提前承諾狀態碼，要連同上述取捨一起評估。

---

## 系統組成

```
                    Client（OpenAI 相容 SDK 即可直接使用）
                              │
                    ┌─────────▼─────────┐
                    │   AI Gateway       │  Spring MVC + 虛擬執行緒
                    │  /v1/chat/...      │  SSE 串流
                    └──┬──────┬──────┬───┘
        路由 / 對沖 ────┘      │      └──── 語意快取（Redis）
                              │
        ┌─────────────┬───────┴──────┬──────────────┐
        ▼             ▼              ▼              ▼
     OpenAI       Anthropic       Ollama        Qdrant（RAG 檢索）
                              │
                    使用量與成本 → PostgreSQL
                    指標 → Micrometer → Prometheus → Grafana
```

### 模組切分

| 模組 | 職責 |
| --- | --- |
| `gateway-core` | Provider 抽象、路由策略、對沖、串流原語、Token 計量。不依賴 Spring Web |
| `gateway-providers` | 各家 Provider 的 HTTP 轉接層（含 SSE 解析與錯誤對映）|
| `gateway-server` | Spring Boot 應用：OpenAI 相容 API、SSE 端點、管理端點 |
| `gateway-rag` | 嵌入模型與向量庫存取，檢索增強 |
| `deploy/` | docker-compose（Redis／PostgreSQL／Qdrant／Prometheus／Grafana／假 Provider）|
| `benchmark/` | 壓測負載產生器與報告 |

### 對外 API 刻意與 OpenAI 相容

`POST /v1/chat/completions`、`GET /v1/models`，請求與回應格式對齊 OpenAI。
這樣既有的 SDK（Python `openai`、LangChain、各種前端）只要改 base URL 就能接進來，
不需要為了網關重寫呼叫端——這是企業導入時最實際的落地條件。

---

## 技術選型

| 元件 | 選擇 | 理由 |
| --- | --- | --- |
| JDK | 21 | 虛擬執行緒為正式功能；Structured Concurrency 僅作為展示用選項 |
| Spring Boot | 3.5.x | MVC + 虛擬執行緒（`spring.threads.virtual.enabled=true`）|
| HTTP 用戶端 | `RestClient`（阻塞）| 在虛擬執行緒上阻塞是正確用法，程式碼比 WebClient 直白 |
| 串流 | `SseEmitter` | 與 MVC 相容，逐 chunk 轉發，不需要 Reactor |
| 語意快取 | Redis | 相同或近似的 prompt 直接回快取，是最有效的省錢手段 |
| 使用量／稽核 | PostgreSQL | 成本與 token 用量要能查詢與歸戶，不能只留在指標裡 |
| 向量庫 | **Qdrant** | 單一容器即可跑；Milvus 需要 etcd + MinIO，本機開發成本高 |
| 韌性 | Resilience4j | 每個 Provider 獨立熔斷與限流 |
| 可觀測性 | Micrometer + Prometheus + Grafana | 重點指標是 TTFT、tokens/s、每請求成本 |
| 測試 | JUnit 5 + Testcontainers + 假 Provider | 絕不在測試中呼叫真的 Provider |

連接埠一律可用環境變數覆寫。這台機器上 6379／9090／3002 已被 FlowSentinel 佔用，
本專案預設改用 6380（Redis）、5433（PostgreSQL）、6333（Qdrant）、9091（Prometheus）、
3003（Grafana）、8090（應用）。

---

## 開發約定

- **金鑰只從環境變數讀取**，不寫進任何設定檔或測試資源。錯誤訊息與日誌一律不得包含金鑰，
  Provider 回應的錯誤要重新包裝後再往外傳。
- **prompt 與回應預設不落地**。要記錄時只留 hash 與 token 數；需要完整內容的稽核模式
  必須是明確開啟的選項，並在文件標明其隱私影響。
- **Provider 的輸出是不可信輸入**。網關若支援工具呼叫，模型要求執行的動作必須經過
  白名單與權限檢查——把模型輸出直接當指令執行是這類系統最典型的破口。
- **每個請求都要有成本歸戶**（租戶／API key／功能標籤），否則成本失控時查不出源頭。
- **重試必須有退避且有次數上限**，且只重試冪等失敗（連線錯誤、5xx、429）。
  對 LLM 而言，盲目重試等於盲目付錢。
- 時間單位一律毫秒；金額一律以最小貨幣單位的整數存放，不要用浮點數存錢。

---

## 已知待釐清的設計問題

實作時要先解決，不要照著原始構想直接寫：

1. 🔲 **Token 計數的準確度**。要在送出前擋住超過上下文長度的請求，就得先算 token，
   而各家的 tokenizer 不同（OpenAI 用 tiktoken 家族、Anthropic 另有一套）。
   先決定：用近似值（字元數 ÷ 係數）快速擋掉明顯超標，或引入 tokenizer 函式庫求精確。
   兩者的誤判成本不同，要寫進文件。
2. 🔲 **語意快取的正確性邊界**。近似 prompt 回同一個答案在多數情境可接受，
   但帶有時效性或個人化內容的請求不行。需要可以按端點或標籤停用快取。
3. 🔲 **串流中途失敗如何呈現**。已經送出一半的 SSE 無法「收回」，
   要定義錯誤事件格式，讓客戶端能分辨「正常結束」與「中途斷掉」。
4. 🔲 **RAG 的嵌入一致性**。索引與查詢必須用同一個嵌入模型與維度，
   換模型就得重建索引。需要在啟動時檢查向量庫的維度與設定是否相符，不符就拒絕啟動。
5. 🔲 **多租戶隔離的層級**。是每租戶獨立限流與預算，或只做歸戶統計？
   這決定資料表設計與路由是否需要租戶維度。
6. ✅ **每日成本上限**。已實作 `BudgetLedger`：送出前以 `maxTokens` 估最壞情況**預扣**，
   實際成本出來後結算差額。預扣而非事後記帳，是因為事後記帳擋不住併發——
   一百個請求同時通過檢查、同時發現額度還夠，然後一起超支。
   超限時回 429 `budget_exceeded`，且**完全不會打到上游**（實測驗證）。
   支出與上限輸出成 Prometheus 指標：看不見的上限只有一半用處，
   撞牆前要看得到「今天已經花了七成」，否則防線退化成一次突然的故障。
7. ✅ **成本歸戶**。`parseUsage` 現在有兩層兜底：上游沒回成本 → 用 `ModelCapability`
   的單價推算；上游連 usage 都沒回（**串流時很常見**，OpenAI 要另外送
   `stream_options.include_usage`）→ 用本地估算的 token 數推算，並在串流結束前
   補一個帶用量的 chunk。估算會有誤差，但**估錯的成本遠小於記成零的成本**：
   前者是帳目偏差幾個百分點，後者是所有以成本為基礎的防線全部失效。
   假 Provider 因此加了 `omitUsage` 設定來重現真貨的限制——
   替身該模擬的是真貨的**限制**，不只是它的能力。
8. 🔲 **成本上限目前是單節點的帳**。`InMemoryBudgetLedger` 把帳記在行程記憶體裡，
   多節點部署時每個節點各記各的，實際總上限會變成 **節點數 × 設定值**。
   與熔斷器狀態同理，要真正的全域上限就得把帳記在 Redis。
   `BudgetLedger` 已經是介面，換掉實作是機械性的工作，但**在換掉之前，
   設定檔上的 $50 在三個節點上就是 $150**，這件事不能忘記。
9. 🔲 **故障 Provider 的探測恢復**。目前失敗率是滾動窗口，所以恢復是可能的，
   但恢復的前提是「還會被選中」。若一家 Provider 連續失敗到排序墊底，而前面的候選
   都健康，它就永遠不會再被呼叫，也就永遠不會刷新統計——窗口化只解決了一半。
   完整的解法是熔斷器的半開探測（隔一段時間放行一個請求去試）。
   在那之前，恢復實際上依賴「前面的候選也出問題」這個偶然條件。

---

## 對沖與失敗切換是兩件事

這是實作 `HedgingChatClient` 時付出代價才釐清的區別，寫在這裡避免之後改壞：

| | 對沖（hedge） | 失敗切換（failover） |
| --- | --- | --- |
| 觸發條件 | 前一個候選**還在飛**，只是慢 | 已啟動的候選**全部失敗** |
| 性質 | 可選。拿錢換延遲 | 必要。不做就等於放棄多 Provider 架構 |
| 成本 | 多打一家 = 多付一次錢 | 前一家已經失敗，本來就沒產出 |
| 是否受 `max-attempts` 限制 | **是** | **否** |

早期版本把兩者算在同一個預算裡，症狀是：主要 Provider 回 429 之後，
備援根本不會被啟動，即使還有兩家健康的候選，請求也直接以 429 失敗。
`max-attempts: 2` 的設定看起來像「最多試兩家」，實際行為卻是「只試一家」。

推論：任何「還要不要再打一家」的判斷，都必須先分清楚是在賭延遲還是在救失敗。

---

## 提交與分支

- 主分支 `main`，功能開發走分支後合併。
- Commit message 用中文、`feat:`／`fix:`／`perf:`／`docs:` 前綴，
  主旨寫「解決了什麼問題」而非「改了哪個檔案」。
