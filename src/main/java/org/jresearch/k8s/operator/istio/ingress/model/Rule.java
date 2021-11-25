package org.jresearch.k8s.operator.istio.ingress.model;

import java.util.List;

import lombok.Value;

@Value(staticConstructor = "of")
public class Rule {
	List<String> hosts;
}
