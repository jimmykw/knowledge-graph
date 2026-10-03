package net.jimmykw.knowledgegraph.chat.routing;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import io.vavr.Tuple2;
import io.vavr.collection.List;
import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

/** Graph summary (document titles, entity types, top entity names) for the classifier; refreshed every 60s as documents can be ingested at runtime. */
@Slf4j
@RequiredArgsConstructor
public class GraphCatalog implements Supplier<GraphProfile> {

    private static final Duration TTL = Duration.ofSeconds(60);
    private static final int MAX_TITLES = 30;
    private static final int MAX_TYPES = 10;
    private static final int MAX_ENTITIES = 15;
    private static final int MAX_TITLE_LENGTH = 100;

    private final Supplier<GraphProfile> loader;
    private final AtomicReference<Tuple2<Long, GraphProfile>> cache = new AtomicReference<>();

    @Override
    public GraphProfile get() {
        val now = System.nanoTime();
        val cached = cache.get();
        if (cached != null && now - cached._1 < TTL.toNanos()) {
            return cached._2;
        }
        val titles = Try.ofSupplier(loader)
                .map(GraphCatalog::tidy)
                .onFailure(error -> log.warn("Routing: could not load graph profile ({})", error.toString()))
                .getOrElse(() -> cached == null ? GraphProfile.empty() : cached._2);
        cache.set(new Tuple2<>(now, titles));
        return titles;
    }

    static GraphProfile tidy(GraphProfile raw) {
        return new GraphProfile(raw.documents().map(GraphCatalog::toTitle).filter(title -> !title.isBlank()).distinct().take(MAX_TITLES),
                raw.entityTypes().distinct().take(MAX_TYPES), raw.topEntities().distinct().take(MAX_ENTITIES));
    }

    private static String toTitle(String filename) {
        val base = filename.trim().replaceAll("(?i)\\.pdf$", "");
        return base.length() > MAX_TITLE_LENGTH ? base.substring(0, MAX_TITLE_LENGTH) : base;
    }
}
