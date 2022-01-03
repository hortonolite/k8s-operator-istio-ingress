package org.jresearch.k8s.operator.istio.ingress;

import static io.fabric8.kubernetes.client.utils.KubernetesResourceUtil.*;
import static org.jresearch.k8s.operator.istio.ingress.model.PathType.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.jresearch.k8s.operator.istio.ingress.model.IngressAnnotation;
import org.jresearch.k8s.operator.istio.ingress.model.IstioMapper;
import org.jresearch.k8s.operator.istio.ingress.model.OperatorMapper;
import org.jresearch.k8s.operator.istio.ingress.model.OwnerInfo;
import org.jresearch.k8s.operator.istio.ingress.model.Path;
import org.jresearch.k8s.operator.istio.ingress.model.Port;
import org.jresearch.k8s.operator.istio.ingress.model.RoutingInfo;
import org.jresearch.k8s.operator.istio.ingress.model.Rule;
import org.jresearch.k8s.operator.istio.ingress.model.Tls;

import io.fabric8.istio.api.networking.v1beta1.Destination;
import io.fabric8.istio.api.networking.v1beta1.DestinationBuilder;
import io.fabric8.istio.api.networking.v1beta1.Gateway;
import io.fabric8.istio.api.networking.v1beta1.GatewayBuilder;
import io.fabric8.istio.api.networking.v1beta1.GatewayList;
import io.fabric8.istio.api.networking.v1beta1.GatewaySpec;
import io.fabric8.istio.api.networking.v1beta1.GatewaySpecBuilder;
import io.fabric8.istio.api.networking.v1beta1.HTTPMatchRequest;
import io.fabric8.istio.api.networking.v1beta1.HTTPMatchRequestBuilder;
import io.fabric8.istio.api.networking.v1beta1.HTTPRoute;
import io.fabric8.istio.api.networking.v1beta1.HTTPRouteBuilder;
import io.fabric8.istio.api.networking.v1beta1.HTTPRouteDestination;
import io.fabric8.istio.api.networking.v1beta1.HTTPRouteDestinationBuilder;
import io.fabric8.istio.api.networking.v1beta1.IsStringMatchMatchType;
import io.fabric8.istio.api.networking.v1beta1.StringMatch;
import io.fabric8.istio.api.networking.v1beta1.StringMatchBuilder;
import io.fabric8.istio.api.networking.v1beta1.StringMatchExact;
import io.fabric8.istio.api.networking.v1beta1.StringMatchExactBuilder;
import io.fabric8.istio.api.networking.v1beta1.StringMatchPrefix;
import io.fabric8.istio.api.networking.v1beta1.StringMatchPrefixBuilder;
import io.fabric8.istio.api.networking.v1beta1.VirtualService;
import io.fabric8.istio.api.networking.v1beta1.VirtualServiceBuilder;
import io.fabric8.istio.api.networking.v1beta1.VirtualServiceList;
import io.fabric8.istio.api.networking.v1beta1.VirtualServiceSpec;
import io.fabric8.istio.api.networking.v1beta1.VirtualServiceSpecBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.LoadBalancerIngress;
import io.fabric8.kubernetes.api.model.LoadBalancerIngressBuilder;
import io.fabric8.kubernetes.api.model.LoadBalancerStatus;
import io.fabric8.kubernetes.api.model.LoadBalancerStatusBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServiceSpec;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRule;
import io.fabric8.kubernetes.api.model.networking.v1.IngressSpec;
import io.fabric8.kubernetes.api.model.networking.v1.IngressStatus;
import io.fabric8.kubernetes.api.model.networking.v1.IngressStatusBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressTLS;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.V1NetworkAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.NetworkAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import one.util.streamex.EntryStream;
import one.util.streamex.StreamEx;

@ApplicationScoped
public class IngressController implements ResourceEventHandler<Ingress> {

	public static final String INGRESS_CLASSNAME = "istio-ingress-oprator";

	@Inject
	KubernetesClient kubernetesClient;
	@Inject
	IstioMapper istioMapper;
	@Inject
	OperatorMapper operatorMapper;

	@Override
	public void onAdd(Ingress obj) {
		getIstioRoutingInfo(obj).ifPresent(info -> Uni.createFrom()
			.item(info)
			.emitOn(Infrastructure.getDefaultExecutor())
			.subscribe()
			.with(this::createOrUpdateIstioResources));
	}

	@Override
	public void onUpdate(Ingress oldObj, Ingress newObj) {
		Optional<RoutingInfo> oldInfo = getIstioRoutingInfo(oldObj);
		Optional<RoutingInfo> newInfo = getIstioRoutingInfo(newObj);
		// ignore the same objects
		if (oldInfo.equals(newInfo)) {
			return;
		} else if (oldInfo.isPresent() && newInfo.isEmpty()) {
			// remove GW and VS
			Uni.createFrom()
				.item(oldInfo.get()
					.toBuilder()
					.ingress(newObj)
					.build())
				.emitOn(Infrastructure.getDefaultExecutor())
				.subscribe()
				.with(this::onDelete);
		} else {
			newInfo.ifPresent(info -> Uni.createFrom()
				.item(info)
				.emitOn(Infrastructure.getDefaultExecutor())
				.subscribe()
				.with(this::createOrUpdateIstioResources));
		}
	}

	@Override
	public void onDelete(Ingress obj, boolean deletedFinalStateUnknown) {
		getIstioRoutingInfo(obj).ifPresent(info -> Uni.createFrom()
			.item(info)
			.emitOn(Infrastructure.getDefaultExecutor())
			.subscribe()
			.with(this::onDelete));
	}

	private void createOrUpdateIstioResources(RoutingInfo info) {
		Log.infof("Create/update istio gateway for: %s", info);
		NonNamespaceOperation<Gateway, GatewayList, Resource<Gateway>> gatewayClient = kubernetesClient.resources(Gateway.class, GatewayList.class).inNamespace(info.getNamespace());
		Gateway gateway = new GatewayBuilder()
			.withMetadata(createMetadata(info.getName(), info.getNamespace(), info.getOwnerInfo()))
			.withSpec(createGatewaySpec(info))
			.build();
		gatewayClient.createOrReplace(gateway);
		createOrUpdateVirtualServices(gateway.getMetadata().getName(), info);
		updateIngressStatus(info);
	}

	private void updateIngressStatus(RoutingInfo info) {
		List<String> istioIngressIps = findIstioIngressIps(info);
		Ingress updated = new IngressBuilder(info.getIngress())
			.withStatus(createIngressStatus(istioIngressIps))
			.build();
		try (NetworkAPIGroupDSL network = kubernetesClient.network(); V1NetworkAPIGroupDSL v1 = network.v1()) {
			v1.ingresses().replaceStatus(updated);
		}
	}

	private static IngressStatus createIngressStatus(List<String> istioIngressIps) {
		return new IngressStatusBuilder()
			.withLoadBalancer(createLoadBalancerStatus(istioIngressIps))
			.build();
	}

	private static LoadBalancerStatus createLoadBalancerStatus(List<String> istioIngressIps) {
		return new LoadBalancerStatusBuilder()
			.withIngress(createLoadBalancerIngress(istioIngressIps))
			.build();
	}

	@SuppressWarnings("resource")
	private static List<LoadBalancerIngress> createLoadBalancerIngress(List<String> istioIngressIps) {
		return StreamEx.of(istioIngressIps)
			.map(IngressController::createLoadBalancerIngress)
			.toList();
	}

	private static LoadBalancerIngress createLoadBalancerIngress(String istioIngressIp) {
		return new LoadBalancerIngressBuilder()
			.withIp(istioIngressIp)
			.build();
	}

	private List<String> findIstioIngressIps(RoutingInfo info) {
		Map<String, String> istioSelector = info.getIstioSelector();
		return kubernetesClient.services()
			.inAnyNamespace()
			.withLabels(istioSelector)
			.list()
			.getItems().stream()
			.findAny()
			.map(Service::getSpec)
			.map(ServiceSpec::getExternalIPs)
			.orElseGet(List::of);
	}

	@SuppressWarnings("resource")
	private void createOrUpdateVirtualServices(String gatewayName, RoutingInfo info) {
		NonNamespaceOperation<VirtualService, VirtualServiceList, Resource<VirtualService>> virtualServiceClient = kubernetesClient.resources(VirtualService.class, VirtualServiceList.class).inNamespace(info.getNamespace());
		EntryStream.of(info.getRules()).forKeyValue((i, rule) -> createOrUpdateVirtualService(virtualServiceClient, i, rule, gatewayName, info));
	}

	@SuppressWarnings("boxing")
	private void createOrUpdateVirtualService(NonNamespaceOperation<VirtualService, VirtualServiceList, Resource<VirtualService>> virtualServiceClient, Integer index, Rule rule, String gatewayName, RoutingInfo info) {
		Log.infof("Create/update istio virtual service %s for: %s", index, info);
		String virtualServiceName = genarateVirtualServiceName(info.getName(), index);
		VirtualService virtualService = new VirtualServiceBuilder()
			.withMetadata(createMetadata(virtualServiceName, info.getNamespace(), info.getOwnerInfo()))
			.withSpec(createVirtualServiceSpec(gatewayName, info.getNamespace(), rule))
			.build();
		virtualServiceClient.createOrReplace(virtualService);
	}

	public static String genarateVirtualServiceName(String baseName, int index) {
		return baseName + "-" + index;
	}

	private VirtualServiceSpec createVirtualServiceSpec(String gatewayName, String namespace, Rule rule) {
		return new VirtualServiceSpecBuilder()
			.withGateways(gatewayName)
			.withHosts(rule.getHost())
			.withHttp(createRoutes(namespace, rule.getPaths()))
			.build();
	}

	@SuppressWarnings("resource")
	private List<HTTPRoute> createRoutes(String namespace, List<Path> paths) {
		return StreamEx.of(paths)
			.map(p -> createRoute(namespace, p))
			.nonNull()
			.toImmutableList();
	}

	private HTTPRoute createRoute(String namespace, Path path) {
		OptionalInt port = getPortNumber(namespace, path);
		return new HTTPRouteBuilder()
			.withRoute(createRoute(port, path.getService().getName()))
			.withMatch(createMatch(path))
			.build();
	}

	private static HTTPMatchRequest createMatch(Path path) {
		return new HTTPMatchRequestBuilder()
			.withUri(createUri(path))
			.build();
	}

	private static StringMatch createUri(Path path) {
		return new StringMatchBuilder()
			.withMatchType(createMatchType(path))
			.build();
	}

	private static IsStringMatchMatchType createMatchType(Path path) {
		if (EXACT == path.getPathType()) {
			return createExactMatchType(path.getPath());
		}
		return createPrefixMatchType(path.getPath());
	}

	private static StringMatchPrefix createPrefixMatchType(String path) {
		return new StringMatchPrefixBuilder()
			.withPrefix(path)
			.build();
	}

	private static StringMatchExact createExactMatchType(String path) {
		return new StringMatchExactBuilder()
			.withExact(path)
			.build();
	}

	private static HTTPRouteDestination createRoute(OptionalInt port, String serviceName) {
		return new HTTPRouteDestinationBuilder()
			.withDestination(port.isEmpty() ? createDestination(serviceName) : createDestination(port.getAsInt(), serviceName))
			.build();
	}

	private static Destination createDestination(String serviceName) {
		return new DestinationBuilder()
			.withHost(serviceName)
			.build();
	}

	@SuppressWarnings("boxing")
	private static Destination createDestination(int port, String serviceName) {
		return new DestinationBuilder()
			.withHost(serviceName)
			.withNewPort(port)
			.build();
	}

	@SuppressWarnings("boxing")
	private OptionalInt getPortNumber(String namespace, Path path) {
		Port port = path.getService().getPort();
		if (port.getNumber() != null) {
			return OptionalInt.of(port.getNumber());
		}
		String serviceName = path.getService().getName();
		Service service = kubernetesClient.services().inNamespace(namespace).withName(serviceName).get();
		if (service == null) {
			return OptionalInt.empty();
		}
		String portName = port.getName();
		return service.getSpec()
			.getPorts()
			.stream()
			.filter(p -> byName(p, portName))
			.findAny()
			.map(ServicePort::getPort)
			.map(OptionalInt::of)
			.orElseGet(OptionalInt::empty);
	}

	private static boolean byName(ServicePort port, String portName) {
		return portName.equalsIgnoreCase(port.getName());
	}

	private GatewaySpec createGatewaySpec(RoutingInfo info) {
		var istioSelector = info.getIstioSelector();
		var tls = info.getTls();
		return info.isHttpsOnly() ? createSpecHttpsOnly(istioSelector, tls) : createSpecWithHttp(istioSelector, tls, info);
	}

	@SuppressWarnings("boxing")
	private GatewaySpec createSpecWithHttp(Map<String, String> istioSelector, List<Tls> tls, RoutingInfo info) {
		return new GatewaySpecBuilder()
			.withSelector(istioSelector)
			.withServers(istioMapper.mapHttps(tls))
			.addToServers(-1, istioMapper.mapHttp(info))
			.build();
	}

	private GatewaySpec createSpecHttpsOnly(Map<String, String> istioSelector, List<Tls> tls) {
		return new GatewaySpecBuilder()
			.withSelector(istioSelector)
			.withServers(istioMapper.mapHttps(tls))
			.build();
	}

	private static ObjectMeta createMetadata(String name, String namespace, OwnerInfo ownerInfo) {
		return new ObjectMetaBuilder()
			.withName(name)
			.withNamespace(namespace)
			.withOwnerReferences(createOwnerReferences(ownerInfo))
			.build();
	}

	@SuppressWarnings("boxing")
	private static OwnerReference createOwnerReferences(OwnerInfo ownerInfo) {
		return new OwnerReferenceBuilder()
			.withApiVersion(ownerInfo.getApiVersion())
			.withBlockOwnerDeletion(true)
			.withController(true)
			.withKind(ownerInfo.getKind())
			.withName(ownerInfo.getName())
			.withUid(ownerInfo.getUid())
			.build();
	}

	@SuppressWarnings("resource")
	private void onDelete(RoutingInfo info) {
		Log.tracef("on delete %s", info);
		// remove GW
		NonNamespaceOperation<Gateway, GatewayList, Resource<Gateway>> gatewayClient = kubernetesClient.resources(Gateway.class, GatewayList.class).inNamespace(info.getNamespace());
		StreamEx.of(gatewayClient.list().getItems()).filter(gw -> isOwned(gw, info.getOwnerInfo().getUid())).findAny().ifPresent(gatewayClient::delete);

		// remove VS
		NonNamespaceOperation<VirtualService, VirtualServiceList, Resource<VirtualService>> virtualServiceClient = kubernetesClient.resources(VirtualService.class, VirtualServiceList.class).inNamespace(info.getNamespace());
		StreamEx.of(virtualServiceClient.list().getItems()).filter(vs -> isOwned(vs, info.getOwnerInfo().getUid())).findAny().ifPresent(virtualServiceClient::delete);

		// remove Ingress status
		boolean needUpdate = Optional.of(info)
			.map(RoutingInfo::getIngress)
			.map(Ingress::getStatus)
			.map(IngressStatus::getLoadBalancer)
			.isPresent();
		if (needUpdate) {
			Ingress updated = new IngressBuilder(info.getIngress())
				.editStatus()
				.withLoadBalancer(null)
				.endStatus()
				.build();
			kubernetesClient.network().v1().ingresses().replaceStatus(updated);
		}
	}

	private static boolean isOwned(HasMetadata object, String uid) {
		return object.getOwnerReferenceFor(uid).isPresent();
	}

	@SuppressWarnings("resource")
	private Optional<RoutingInfo> getIstioRoutingInfo(Ingress ingress) {
		String ingressClassName = Optional.of(ingress).map(Ingress::getSpec).map(IngressSpec::getIngressClassName).orElse(null);
		if (!INGRESS_CLASSNAME.equals(ingressClassName)) {
			Log.infof("Skip ingress %s. Ingress class %s is not a %s", getQualifiedName(ingress), ingressClassName, INGRESS_CLASSNAME);
			return Optional.empty();
		}
		// process owner info

		// ignore defaultBackend (?)

		// process TLS
		List<IngressTLS> ingressTls = Optional.of(ingress).map(Ingress::getSpec).map(IngressSpec::getTls).orElseGet(List::of);
		List<Tls> tls = StreamEx.of(ingressTls).map(istioMapper::map).toImmutableList();

		// process rules
		// Check kubernetes.io/ingress.allow-http annotation

		boolean allowHttpValue = Boolean.parseBoolean(IngressAnnotation.ALLOW_HTTP.getValue(ingress));
		List<IngressRule> ingressRules = Optional.of(ingress).map(Ingress::getSpec).map(IngressSpec::getRules).orElseGet(List::of);
		List<Rule> rules = StreamEx.of(ingressRules)
			.map(operatorMapper::map)
			.toImmutableList();

		String istioSelectorAnnotation = IngressAnnotation.ISTIO_SELECTOR.getValue(ingress);
		var istioSelector = StreamEx.split(istioSelectorAnnotation, ',', true)
			.map(s -> s.split("=", 2))
			.toMap(s -> s[0], s -> s[1]);

		return Optional.of(RoutingInfo.builder()
			.ingress(ingress)
			.name(ingress.getMetadata().getName())
			.namespace(ingress.getMetadata().getNamespace())
			.ownerInfo(istioMapper.map(ingress))
			.istioSelector(istioSelector)
			.tls(tls)
			.httpsOnly(!allowHttpValue)
			.rules(rules)
			.build());

	}

}
