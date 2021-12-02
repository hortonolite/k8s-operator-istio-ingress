package org.jresearch.k8s.operator.istio.ingress.model;

import java.util.List;
import java.util.Map;

import lombok.Builder;
import lombok.Builder.Default;
import lombok.Value;
import one.util.streamex.StreamEx;

@Value
@Builder
public class RoutingInfo {
	boolean httpsOnly;
	String name;
	String namespace;
	OwnerInfo ownerInfo;
	@Default
	Map<String, String> istioSelector = Map.of();
	@Default
	List<Tls> tls = List.of();
	@Default
	List<Rule> rules = List.of();

	@SuppressWarnings("resource")
	public List<String> getHttpHosts() {
		return StreamEx.of(rules)
			.map(Rule::getHost)
			.toImmutableList();
	}

}
