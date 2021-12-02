package org.jresearch.k8s.operator.istio.ingress.model;

import java.util.Map;
import java.util.Optional;

import lombok.AllArgsConstructor;
import lombok.Getter;
import one.util.streamex.StreamEx;

@Getter
@AllArgsConstructor
public enum PathType {
	IMPLEMENTATION_SPECIFIC("ImplementationSpecific"),
	EXACT("Exact"),
	PREFIX("Prefix"),
	;

	private final String type;

	private static final Map<String, PathType> ENUM_MAP = StreamEx.of(values())
		.mapToEntry(PathType::getType, t -> t)
		.toImmutableMap();

	public static Optional<PathType> byString(String type) {
		return Optional.ofNullable(ENUM_MAP.get(type));
	}

}
