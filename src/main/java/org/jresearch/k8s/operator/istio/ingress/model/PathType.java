package org.jresearch.k8s.operator.istio.ingress.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PathType {
	IMPLEMENTATION_SPECIFIC("ImplementationSpecific"),
	EXACT("Exact"),
	PREFIX("Prefix"),
	;

	private final String type;
}
