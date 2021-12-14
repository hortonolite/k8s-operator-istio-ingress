package org.jresearch.k8s.operator.istio.ingress.model;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.utils.KubernetesResourceUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum IngressAnnotation {

	ALLOW_HTTP("kubernetes.io/ingress.allow-http", Boolean.FALSE.toString()),
	ISTIO_SELECTOR("k8s.jresearch.org/istio-selector", "istio=ingressgateway"),

	;

	private final String name;
	private final String defaultValue;

	public String getValue(HasMetadata k8sObject) {
		return KubernetesResourceUtil.getOrCreateAnnotations(k8sObject).getOrDefault(getName(), getDefaultValue());
	}
}
