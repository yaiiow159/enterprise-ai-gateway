package io.aigateway.providers;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aigateway.core.ProviderException;

/**
 * 上游 JSON 的序列化與解析。
 *
 * <p>抽出來的理由不是「少寫幾行」，而是**錯誤分類要一致**：
 * 序列化失敗屬於我們自己的請求有問題（{@code BAD_REQUEST}，不該重試），
 * 解析失敗則是上游回了非預期的內容（{@code SERVER_ERROR}，換一家可能就好了）。
 * 這兩者原本散在三個檔案裡各自判斷，遲早會有人分錯邊，
 * 而分錯邊的後果是「不該重試的一直重試」或「該重試的直接放棄」。
 *
 * <p>{@link ObjectMapper} 是執行緒安全的，共用一個實例即可。
 */
final class ProviderJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProviderJson() {
    }

    /** 序列化送出的請求。失敗代表**我們**組錯了東西，換一家上游也一樣 */
    static String write(String providerName, Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException error) {
            throw new ProviderException(providerName, ProviderException.Kind.BAD_REQUEST, 400,
                    "無法序列化請求內容", error);
        }
    }

    /** 解析上游回應。失敗代表**上游**給了非預期的內容，換一家有機會成功 */
    static JsonNode readTree(String providerName, String json, String what) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException error) {
            throw new ProviderException(providerName, ProviderException.Kind.SERVER_ERROR, 502,
                    what + "不是合法的 JSON", error);
        }
    }

    /**
     * 盡力解析，失敗時回傳 null 而不拋錯。
     *
     * <p>用於「解析不出來也不影響主流程」的場合，例如從錯誤回應裡挖出訊息文字——
     * 那時候真正的錯誤是 HTTP 狀態碼，不該被一個解析例外蓋掉。
     */
    static JsonNode readTreeQuietly(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException ignored) {
            return null;
        }
    }

    /** 供仍需要直接操作 ObjectMapper 的場合（例如建立空節點） */
    static ObjectMapper mapper() {
        return MAPPER;
    }
}
