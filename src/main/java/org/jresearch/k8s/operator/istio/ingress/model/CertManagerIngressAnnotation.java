package org.jresearch.k8s.operator.istio.ingress.model;

import java.util.Optional;

import org.graalvm.collections.Pair;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.utils.KubernetesResourceUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum CertManagerIngressAnnotation {

	ISSUER("k8s.jresearch.org/cert-manager.io-issuer"),
	CLUSTER_ISSUER("k8s.jresearch.org/cert-manager.io-cluster-issuer"),
	ISSUER_KIND("k8s.jresearch.org/cert-manager.io-issuer-kind"),
	ISSUER_GROUP("k8s.jresearch.org/cert-manager.io-issuer-group"),
	COMMON_NAME("k8s.jresearch.org/cert-manager.io-common-name"),
	DURATION("k8s.jresearch.org/cert-manager.io-duration"),
	RENEW_BEFORE("k8s.jresearch.org/cert-manager.io-renew-before"),
	USAGES("k8s.jresearch.org/cert-manager.io-usages"),
	;

	private final String name;

	public Optional<Pair<String, String>> getValue(HasMetadata k8sObject) {
		String value = KubernetesResourceUtil.getOrCreateAnnotations(k8sObject).get(getName());
		return Optional.ofNullable(value)
			.map(v -> Pair.create(getCertManagerName(), v));
	}

	public String getCertManagerName() {
		return name.substring("k8s.jresearch.org/".length()).replace("cert-manager.io-", "cert-manager.io/");
	}

}
