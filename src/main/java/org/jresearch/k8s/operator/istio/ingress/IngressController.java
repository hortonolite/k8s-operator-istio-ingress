package org.jresearch.k8s.operator.istio.ingress;

import static io.fabric8.kubernetes.client.utils.KubernetesResourceUtil.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.jresearch.k8s.operator.istio.ingress.model.IngressAnnotation;
import org.jresearch.k8s.operator.istio.ingress.model.IstioMapper;
import org.jresearch.k8s.operator.istio.ingress.model.RoutingInfo;
import org.jresearch.k8s.operator.istio.ingress.model.Rule;
import org.jresearch.k8s.operator.istio.ingress.model.Tls;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRule;
import io.fabric8.kubernetes.api.model.networking.v1.IngressSpec;
import io.fabric8.kubernetes.api.model.networking.v1.IngressTLS;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import me.snowdrop.istio.api.networking.v1beta1.Gateway;
import me.snowdrop.istio.api.networking.v1beta1.GatewayBuilder;
import me.snowdrop.istio.api.networking.v1beta1.GatewayList;
import me.snowdrop.istio.api.networking.v1beta1.GatewaySpec;
import me.snowdrop.istio.api.networking.v1beta1.GatewaySpecBuilder;
import me.snowdrop.istio.api.networking.v1beta1.VirtualService;
import me.snowdrop.istio.api.networking.v1beta1.VirtualServiceBuilder;
import me.snowdrop.istio.api.networking.v1beta1.VirtualServiceList;
import one.util.streamex.StreamEx;

@ApplicationScoped
public class IngressController implements ResourceEventHandler<Ingress> {

	public static final String INGRESS_CLASSNAME = "IstioIngressOprator";

	@Inject
	KubernetesClient kubernetesClient;
	@Inject
	IstioMapper istioMapper;

	@Override
	public void onAdd(Ingress obj) {
		Uni.createFrom()
			.item(obj)
			.emitOn(Infrastructure.getDefaultExecutor())
			.subscribe()
			.with(this::onAddOrUpdate);
	}

	@Override
	public void onUpdate(Ingress oldObj, Ingress newObj) {
		// ignore the same objects
		Optional<String> oldVersion = Optional.ofNullable(oldObj).map(Ingress::getMetadata).map(ObjectMeta::getResourceVersion);
		Optional<String> newVersion = Optional.ofNullable(newObj).map(Ingress::getMetadata).map(ObjectMeta::getResourceVersion);
		if (oldVersion.equals(newVersion)) {
			return;
		}
		Uni.createFrom()
			.item(newObj)
			.emitOn(Infrastructure.getDefaultExecutor())
			.subscribe()
			.with(this::onAddOrUpdate);
	}

	@Override
	public void onDelete(Ingress obj, boolean deletedFinalStateUnknown) {
		Uni.createFrom()
			.item(obj)
			.emitOn(Infrastructure.getDefaultExecutor())
			.subscribe()
			.with(this::onAddOrUpdate);
		Log.tracef("delete %s", getQualifiedName(obj));
	}

	private void onAddOrUpdate(Ingress ingress) {
		Log.tracef("on add/update %s", getQualifiedName(ingress));
		List<RoutingInfo> istioPratameters = getIstioRoutingInfo(ingress);
		istioPratameters.forEach(this::createOrUpdateIstioResources);
	}

	private void createOrUpdateIstioResources(RoutingInfo info) {
		Log.infof("Create/update istio gateway for: %s", info);
		NonNamespaceOperation<Gateway, GatewayList, Resource<Gateway>> gatewayClient = kubernetesClient.resources(Gateway.class, GatewayList.class).inNamespace(info.getNamespace());
		Gateway gateway = new GatewayBuilder()
			.withMetadata(createMetadata(info.getName(), info.getNamespace()))
			.withSpec(createSpec(info.getIstioSelector(), info.getTls(), info.getRule()))
			.build();
		gatewayClient.createOrReplace(gateway);
		Log.infof("Create/update istio virtual service for: %s", info);
		NonNamespaceOperation<VirtualService, VirtualServiceList, Resource<VirtualService>> virtualServiceClient = kubernetesClient.resources(VirtualService.class, VirtualServiceList.class).inNamespace(info.getNamespace());
		VirtualService virtualService = new VirtualServiceBuilder()
			.withMetadata(createMetadata(info.getName(), info.getNamespace()))
			.build();
		virtualServiceClient.createOrReplace(virtualService);
	}

	private GatewaySpec createSpec(Map<String, String> istioSelector, List<Tls> tls, Rule rule) {
		return rule.getHosts().isEmpty() ? createSpecHttpsOnly(istioSelector, tls) : createSpecWithHttp(istioSelector, tls, rule);
	}

	private GatewaySpec createSpecWithHttp(Map<String, String> istioSelector, List<Tls> tls, Rule rule) {
		return new GatewaySpecBuilder()
			.withSelector(istioSelector)
			.withServers(istioMapper.mapHttps(tls))
			.addToServers(-1, istioMapper.mapHttp(rule))
			.build();
	}

	private GatewaySpec createSpecHttpsOnly(Map<String, String> istioSelector, List<Tls> tls) {
		return new GatewaySpecBuilder()
			.withSelector(istioSelector)
			.withServers(istioMapper.mapHttps(tls))
			.build();
	}

	private static ObjectMeta createMetadata(String name, String namespace) {
		return new ObjectMetaBuilder()
			.withName(name)
			.withNamespace(namespace)
			.build();
	}

	private void onDelete(Ingress ingress) {
		Log.tracef("on delete %s", getQualifiedName(ingress));
		// should be done automatically
	}

	@SuppressWarnings("resource")
	private List<RoutingInfo> getIstioRoutingInfo(Ingress ingress) {
		String ingressClassName = Optional.of(ingress).map(Ingress::getSpec).map(IngressSpec::getIngressClassName).orElse(null);
		if (!INGRESS_CLASSNAME.equals(ingressClassName)) {
			Log.infof("Skip ingress %s. Ingress class %s is not a %s", getQualifiedName(ingress), ingressClassName, INGRESS_CLASSNAME);
			return List.of();
		}
		// ignore defaultBackend (?)

		// process TLS
		List<IngressTLS> ingressTls = Optional.of(ingress).map(Ingress::getSpec).map(IngressSpec::getTls).orElseGet(List::of);
		List<Tls> tls = StreamEx.of(ingressTls).map(istioMapper::map).toImmutableList();

		// process rules
		// Check kubernetes.io/ingress.allow-http annotation

		String allowHttpValue = IngressAnnotation.ALLOW_HTTP.getValue(ingress);
		List<IngressRule> ingressRules = Optional.of(ingress).filter(i -> Boolean.parseBoolean(allowHttpValue)).map(Ingress::getSpec).map(IngressSpec::getRules).orElseGet(List::of);
		List<String> httpHosts = StreamEx.of(ingressRules).map(IngressRule::getHost).toImmutableList();

		String istioSelectorAnnotation = IngressAnnotation.ISTIO_SELECTOR.getValue(ingress);

		var istioSelector = StreamEx.split(istioSelectorAnnotation, ',', true)
			.map(s -> s.split("=", 2))
			.toMap(s -> s[0], s -> s[1]);

		return List.of(new RoutingInfo(ingress.getMetadata().getName(), ingress.getMetadata().getNamespace(), istioSelector, tls, Rule.of(httpHosts)));
	}

}
