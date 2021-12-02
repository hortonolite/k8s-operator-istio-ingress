package org.jresearch.k8s.operator.istio.ingress.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class OwnerInfo {
	String apiVersion;
	String kind;
	String name;
	String uid;
}
