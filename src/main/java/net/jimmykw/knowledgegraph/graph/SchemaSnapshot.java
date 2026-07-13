package net.jimmykw.knowledgegraph.graph;

import java.util.List;

public record SchemaSnapshot(List<String> labels, List<String> relTypes) {
}
