package io.aigateway.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 把串流裡的工具呼叫增量組回完整的工具呼叫。
 *
 * <p>非串流請求在網關內部也走串流（對沖要以首個 token 決勝負），所以要自己組回
 * OpenAI {@code message.tool_calls} 的樣子。增量以 {@code index} 對應同一個呼叫，
 * 字串欄位（函式名稱、參數）逐段串接，其餘欄位取第一次出現的值。
 */
public final class ToolCallAccumulator {

    private final Map<Integer, Call> calls = new TreeMap<>();

    public void add(List<Map<String, Object>> deltas) {
        if (deltas == null) return;
        for (Map<String, Object> delta : deltas) {
            int index = delta.get("index") instanceof Number number ? number.intValue() : calls.size();
            Call call = calls.computeIfAbsent(index, unused -> new Call());
            if (delta.get("id") instanceof String id && call.id == null) call.id = id;
            if (delta.get("type") instanceof String type && call.type == null) call.type = type;
            if (delta.get("function") instanceof Map<?, ?> function) {
                if (function.get("name") instanceof String name) call.name.append(name);
                if (function.get("arguments") instanceof String arguments) call.arguments.append(arguments);
            }
        }
    }

    /** @return OpenAI {@code message.tool_calls} 格式；沒有工具呼叫時為空清單 */
    public List<Map<String, Object>> result() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Call call : calls.values()) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", call.name.toString());
            function.put("arguments", call.arguments.toString());

            Map<String, Object> toolCall = new LinkedHashMap<>();
            toolCall.put("id", call.id);
            toolCall.put("type", call.type == null ? "function" : call.type);
            toolCall.put("function", function);
            result.add(toolCall);
        }
        return result;
    }

    private static final class Call {
        private String id;
        private String type;
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }
}
