package net.jimmykw.knowledgegraph.graph;

import io.vavr.Tuple;
import io.vavr.Tuple2;
import io.vavr.collection.HashMap;
import io.vavr.collection.List;
import io.vavr.control.Option;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ExtractedEntity;

@Slf4j
public final class CanonicalIndex {

    private final HashMap<Tuple2<String, String>, Long> byKey;
    private final List<ExtractedEntity> entities;

    private CanonicalIndex(HashMap<Tuple2<String, String>, Long> byKey, List<ExtractedEntity> entities) {
        this.byKey = byKey;
        this.entities = entities;
    }

    public static CanonicalIndex empty() {
        return new CanonicalIndex(HashMap.empty(), List.empty());
    }

    public CanonicalIndex put(ExtractedEntity entity, long nodeId) {
        val key = Tuple.of(EntityNormalizer.normalize(entity.name()), entity.label());
        if (byKey.containsKey(key)) {
            return this;
        }
        return new CanonicalIndex(byKey.put(key, nodeId), entities.append(entity));
    }

    public Option<Long> lookup(String name, String label) {
        val exact = byKey.get(Tuple.of(EntityNormalizer.normalize(name), label));
        if (exact.isDefined()) {
            return exact;
        }
        val byName = byKey
                .filter(entry -> entry._1()._1().equals(EntityNormalizer.normalize(name)))
                .headOption()
                .map(entry -> entry._2());
        if (byName.isDefined()) {
            log.debug("Lookup fallback: '{}' [{}] matched by name only (label mismatch)", name, label);
        }
        return byName;
    }

    public List<ExtractedEntity> entities() {
        return entities;
    }

    public int size() {
        return entities.size();
    }
}
