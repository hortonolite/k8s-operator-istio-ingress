package org.jresearch.k8s.operator.istio.ingress.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
public class Service {
	String name;
	Port port;
}
