package org.jresearch.k8s.operator.istio.ingress.model;

import java.util.List;
import java.util.Map;

import lombok.Value;

@Value
public class RoutingInfo {
	String name;
	String namespace;
	Map<String, String> istioSelector;
	List<Tls> tls;
	Rule rule;

	// gateway (one per host) or not?
	// cert-manager annotations
	// external-dns?
	// istio ingress selector

	// virtual server (one per gateway)
	// gateways: one above
	// hosts:
	// - '*'
	// [http] map from ingress rules

	// QUESTION
	// name - same as ingress - should be different: one ingress -> few GW (one per
	// host)

	// DONE
	// GW name - same as ingress
	// namespace - same as ingress
	// ingressClass IstioIngressOperator

	// gateway
	// owner - ingress
	// for each TLS from ingress port (https, 443, HTTPS), hosts from TLS,
	// credentialName: from TLS + SIMPLE
	// and (depends on annotation kubernetes.io/ingress.allow-http: "false"/"true")
	// if absent default "false"
	// for each rule from ingress port (http, 80, HTTP) hosts from rule

	// virtual server
	// owner - ingress
}
