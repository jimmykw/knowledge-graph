package net.jimmykw.knowledgegraph.resolve;

import io.vavr.collection.List;
import lombok.experimental.UtilityClass;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.Candidate;

@UtilityClass
class ResolutionFixtures {

    static Candidate exact(int idx, String id, String name, String label, String... aliases) {
        return new Candidate(idx, id, name, label, name + " node", List.of(aliases).prepend(name), List.of("-[USES]-> Thing"), true);
    }

    static Candidate fuzzy(int idx, String id, String name, String label) {
        return new Candidate(idx, id, name, label, name + " node", List.of(name), List.empty(), false);
    }
}
