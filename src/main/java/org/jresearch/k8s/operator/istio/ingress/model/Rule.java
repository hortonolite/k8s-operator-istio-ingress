package org.jresearch.k8s.operator.istio.ingress.model;

import java.util.List;

import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
public class Rule {
	String host;
	List<Path> paths;
}
