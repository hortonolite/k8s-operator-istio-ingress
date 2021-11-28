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
import org.jresearch.k8s.operator.istio.ingress.model.Path;
import org.jresearch.k8s.operator.istio.ingress.model.Port;
import org.jresearch.k8s.operator.istio.ingress.model.RoutingInfo;
import org.jresearch.k8s.operator.istio.ingress.model.Rule;
import org.jresearch.k8s.operator.istio.ingress.model.Tls;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
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
import me.snowdrop.istio.api.networking.v1beta1.Destination;
import me.snowdrop.istio.api.networking.v1beta1.DestinationBuilder;
import me.snowdrop.istio.api.networking.v1beta1.ExactMatchType;
import me.snowdrop.istio.api.networking.v1beta1.ExactMatchTypeBuilder;
import me.snowdrop.istio.api.networking.v1beta1.Gateway;
import me.snowdrop.istio.api.networking.v1beta1.GatewayBuilder;
import me.snowdrop.istio.api.networking.v1beta1.GatewayList;
import me.snowdrop.istio.api.networking.v1beta1.GatewaySpec;
import me.snowdrop.istio.api.networking.v1beta1.GatewaySpecBuilder;
import me.snowdrop.istio.api.networking.v1beta1.HTTPMatchRequest;
import me.snowdrop.istio.api.networking.v1beta1.HTTPMatchRequestBuilder;
import me.snowdrop.istio.api.networking.v1beta1.HTTPRoute;
import me.snowdrop.istio.api.networking.v1beta1.HTTPRouteBuilder;
import me.snowdrop.istio.api.networking.v1beta1.HTTPRouteDestination;
import me.snowdrop.istio.api.networking.v1beta1.HTTPRouteDestinationBuilder;
import me.snowdrop.istio.api.networking.v1beta1.PrefixMatchType;
import me.snowdrop.istio.api.networking.v1beta1.PrefixMatchTypeBuilder;
import me.snowdrop.istio.api.networking.v1beta1.StringMatch;
import me.snowdrop.istio.api.networking.v1beta1.StringMatch.MatchType;
import me.snowdrop.istio.api.networking.v1beta1.StringMatchBuilder;
import me.snowdrop.istio.api.networking.v1beta1.VirtualService;
import me.snowdrop.istio.api.networking.v1beta1.VirtualServiceBuilder;
import me.snowdrop.istio.api.networking.v1beta1.VirtualServiceList;
import me.snowdrop.istio.api.networking.v1beta1.VirtualServiceSpec;
import me.snowdrop.istio.api.networking.v1beta1.VirtualServiceSpecBuilder;
import one.util.streamex.EntryStream;
import one.util.streamex.StreamEx;

@ApplicationScoped
public class IngressController implements ResourceEventHandler<Ingress> {

	public static final String INGRESS_CLASSNAME = "IstioIngressOprator";

	@Inject
	KubernetesClient kubernetesClient;
	@Inject
	IstioMapper istioMapper;
	@Inject
	OperatorMapper operatorMapper;

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
			.with(this::onDelete);
		Log.tracef("delete %s", getQualifiedName(obj));
	}

	private void onAddOrUpdate(Ingress ingress) {
		Log.tracef("on add/update %s", getQualifiedName(ingress));
		Optional<RoutingInfo> istioPratameters = getIstioRoutingInfo(ingress);
		istioPratameters.ifPresent(this::createOrUpdateIstioResources);
	}

	private void createOrUpdateIstioResources(RoutingInfo info) {
		Log.infof("Create/update istio gateway for: %s", info);
		NonNamespaceOperation<Gateway, GatewayList, Resource<Gateway>> gatewayClient = kubernetesClient.resources(Gateway.class, GatewayList.class).inNamespace(info.getNamespace());
		Gateway gateway = new GatewayBuilder()
			.withMetadata(createMetadata(info.getName(), info.getNamespace()))
			.withSpec(createGatewaySpec(info))
			.build();
		gatewayClient.createOrReplace(gateway);
		createOrUpdateVirtualServices(gateway.getMetadata().getName(), info);
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
			.withMetadata(createMetadata(virtualServiceName, info.getNamespace()))
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

	private static MatchType createMatchType(Path path) {
		if (Exact == path.getPathType()) {
			return createExactMatchType(path.getPath());
		}
		return createPrefixMatchType(path.getPath());
	}

	private static PrefixMatchType createPrefixMatchType(String path) {
		return new PrefixMatchTypeBuilder()
			.withPrefix(path)
			.build();
	}

	private static ExactMatchType createExactMatchType(String path) {
		return new ExactMatchTypeBuilder()
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
	private Optional<RoutingInfo> getIstioRoutingInfo(Ingress ingress) {
		String ingressClassName = Optional.of(ingress).map(Ingress::getSpec).map(IngressSpec::getIngressClassName).orElse(null);
		if (!INGRESS_CLASSNAME.equals(ingressClassName)) {
			Log.infof("Skip ingress %s. Ingress class %s is not a %s", getQualifiedName(ingress), ingressClassName, INGRESS_CLASSNAME);
			return Optional.empty();
		}
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
			.name(ingress.getMetadata().getName())
			.namespace(ingress.getMetadata().getNamespace())
			.istioSelector(istioSelector)
			.tls(tls)
			.httpsOnly(!allowHttpValue)
			.rules(rules)
			.build());

	}

}
