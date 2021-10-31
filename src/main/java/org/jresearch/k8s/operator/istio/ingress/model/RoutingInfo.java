package org.jresearch.k8s.operator.istio.ingress.model;

public record RoutingInfo() {
	// gateway (one per host)
	// cert-manager annotations
	// external-dns?
	// name - same as ingress
	// namespace - same as ingress
	// owner - ingress
	// istio ingress selector
	// [servers]
	// [hosts] always one from rules -> paths
	// port
	// name: https
	// number: 443
	// protocol: HTTPS
	// and (if there is no annotation kubernetes.io/ingress.allow-http: "false")
	// name: http
	// number: 80
	// protocol: HTTP
	// tls
	// credentialName: from ingress
	// mode: SIMPLE

	// virtual server (one per gateway)
	// gateways: one above
	// hosts:
	// - '*'
	// [http] map from ingress rules

}
