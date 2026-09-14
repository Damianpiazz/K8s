package com.ecommerce.search.service;

import com.ecommerce.search.model.ProductSearchHit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * In-memory product search.
 *
 * <p>The index is a small static catalog (read-only, mirrors catalog-svc seed
 * data plus a few extras). Scoring is a simple contains-score per the spec:
 * name match = 3, description match = 1, tag match = 1; results are ordered by
 * score descending. Query terms are recorded so {@code GET /api/search/hot} can
 * return the most popular searches.
 *
 * <p>Production design: a real search index (Azure AI Search / Elasticsearch)
 * hosted outside the service, with the REST contract unchanged.
 */
@Service
public class SearchService {

    /** Seed catalog entry — internal index row. */
    record CatalogProduct(long id, String name, String description,
                          String category, List<String> tags) {
    }

    /** Static demo catalog — ids 1-8 match inventory-svc and the catalog seed. */
    private static final List<CatalogProduct> CATALOG = List.of(
            new CatalogProduct(1L, "Mouse Inalámbrico MX-10",
                    "Mouse inalámbrico ergonómico de 2,4 GHz", "Accesorios",
                    List.of("mouse", "inalámbrico", "ergonómico")),
            new CatalogProduct(2L, "Teclado Mecánico K87",
                    "Hot-swappable, RGB, switches azules", "Accesorios",
                    List.of("teclado", "mecánico", "rgb")),
            new CatalogProduct(3L, "Monitor IPS 27\"",
                    "2560x1440, 75 Hz, calibrado de fábrica", "Pantallas",
                    List.of("monitor", "ips", "pantalla")),
            new CatalogProduct(4L, "Estación de Acoplamiento USB-C",
                    "Doble HDMI, 100 W PD, 10 Gbps", "Accesorios",
                    List.of("acoplamiento", "usb-c", "hdmi")),
            new CatalogProduct(5L, "Soporte para Laptop Pro",
                    "Aluminio, altura regulable, plegable", "Accesorios",
                    List.of("soporte", "laptop", "aluminio")),
            new CatalogProduct(6L, "Cámara Web HD 1080p",
                    "Full-HD, autoenfoque, obturador de privacidad", "Accesorios",
                    List.of("webcam", "hd", "video")),
            new CatalogProduct(7L, "Auriculares Bluetooth NC",
                    "Over-ear, cancelación activa de ruido", "Audio",
                    List.of("auriculares", "bluetooth", "cancelación de ruido")),
            new CatalogProduct(8L, "Smartwatch S2",
                    "AMOLED, GPS, batería de 10 días", "Vestibles",
                    List.of("smartwatch", "gps", "fitness"))
    );

    private final int maxResults;
    private final int hotLimit;
    private final ConcurrentHashMap<String, AtomicLong> searchCounts =
            new ConcurrentHashMap<>();

    public SearchService(@Value("${search.max-results:10}") int maxResults,
                         @Value("${search.hot-limit:5}") int hotLimit) {
        this.maxResults = maxResults;
        this.hotLimit = hotLimit;
    }

    /**
     * Case-insensitive contains search over the catalog.
     *
     * @param q        query term (blank → empty result list)
     * @param category optional exact-match category filter (case-insensitive)
     * @return matching hits ordered by score descending, capped at max-results
     */
    public List<ProductSearchHit> search(String q, String category) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        String needle = q.trim().toLowerCase(Locale.ROOT);
        searchCounts.computeIfAbsent(needle, k -> new AtomicLong()).incrementAndGet();

        Stream<CatalogProduct> candidates = CATALOG.stream();
        if (category != null && !category.isBlank()) {
            String wanted = category.trim().toLowerCase(Locale.ROOT);
            candidates = candidates.filter(p -> p.category().toLowerCase(Locale.ROOT).equals(wanted));
        }
        return candidates
                .map(p -> new ProductSearchHit(p.id(), p.name(), p.description(),
                        p.category(), score(p, needle)))
                .filter(hit -> hit.score() > 0)
                .sorted(Comparator.comparingDouble(ProductSearchHit::score)
                        .reversed()
                        .thenComparing(ProductSearchHit::id))
                .limit(maxResults)
                .toList();
    }

    /** Most frequent query terms, most recent first, capped at hot-limit. */
    public List<String> hotSearches() {
        return searchCounts.entrySet().stream()
                .sorted(Comparator
                        .comparingLong((Map.Entry<String, AtomicLong> e) -> e.getValue().get())
                        .reversed())
                .limit(hotLimit)
                .map(Map.Entry::getKey)
                .toList();
    }

    /** Spec scoring: name 3, description 1, tag 1 (all contains, case-insensitive). */
    private static double score(CatalogProduct product, String needle) {
        double s = 0.0;
        if (product.name().toLowerCase(Locale.ROOT).contains(needle)) {
            s += 3;
        }
        if (product.description().toLowerCase(Locale.ROOT).contains(needle)) {
            s += 1;
        }
        for (String tag : product.tags()) {
            if (tag.toLowerCase(Locale.ROOT).contains(needle)) {
                s += 1;
                break;
            }
        }
        return s;
    }
}