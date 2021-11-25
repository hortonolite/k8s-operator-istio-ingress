package org.jresearch.k8s.operator.istio.ingress.model;

import java.util.List;

import lombok.Value;

@Value
public class Tls {
	String secret;
	List<String> hosts;
}
