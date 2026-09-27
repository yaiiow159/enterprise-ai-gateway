package io.aigateway.core;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/** 已註冊的 Provider 與它們的滾動統計 */
public final class ProviderRegistry {

    private final Map<String, LlmProvider> providers = new ConcurrentHashMap<>();
    private final Map<String, ProviderStats> stats = new ConcurrentHashMap<>();

    /**
     * 依註冊順序保存的同一份清單。
     *
     * <p>只靠 {@code ConcurrentHashMap#values()} 迭代會得到雜湊順序：剛啟動、所有 Provider
     * 統計都還是空的時候，排序鍵全部相等，穩定排序保留的就是這個**任意**順序——
     * 於是「主要 Provider 是哪一家」在冷啟動時是不確定的，而設定檔裡刻意排在第一位的那家
     * （通常是營運上偏好的主力）會被無聲忽略。註冊順序是操作者表達優先權的方式，要保留。
     */
    private final List<LlmProvider> ordered = new CopyOnWriteArrayList<>();

    public void register(LlmProvider provider) {
        LlmProvider previous = providers.put(provider.name(), provider);
        if (previous != null) ordered.remove(previous);
        ordered.add(provider);
        stats.computeIfAbsent(provider.name(), ProviderStats::new);
    }

    public List<LlmProvider> all() {
        return List.copyOf(ordered);
    }

    public Optional<LlmProvider> byName(String name) {
        return Optional.ofNullable(providers.get(name));
    }

    public ProviderStats statsFor(String providerName) {
        return stats.computeIfAbsent(providerName, ProviderStats::new);
    }

    public Map<String, ProviderStats> allStats() {
        return Map.copyOf(stats);
    }

    /**
     * 能承接這個請求的 Provider：服務該模型名稱，且能力足以處理這個請求。
     *
     * <p>先濾能力再談排序：上下文塞不下、不支援串流的 Provider 不管多快多便宜都不能用，
     * 送出去只是換一個比較慢的失敗。
     */
    public List<LlmProvider> candidatesFor(ChatRequest request) {
        return ordered.stream()
                .filter(provider -> provider.servedModels().contains(request.model()))
                .filter(provider -> provider.capability().canHandle(request))
                .collect(Collectors.toList());
    }

    /** 是否有任何 Provider 服務這個模型名稱，用來區分「模型不存在」與「能力不足」 */
    public boolean servesModel(String model) {
        return ordered.stream().anyMatch(provider -> provider.servedModels().contains(model));
    }
}
