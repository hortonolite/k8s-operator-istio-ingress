package org.jresearch.k8s.operator.istio.ingress.model;

import lombok.Value;

@Value
public class Port {
	Integer number;
	String name;
}
