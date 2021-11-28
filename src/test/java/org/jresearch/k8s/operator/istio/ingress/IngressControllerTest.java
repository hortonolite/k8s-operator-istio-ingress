package org.jresearch.k8s.operator.istio.ingress;

import static org.awaitility.Awaitility.*;
import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.inject.Inject;

import org.hamcrest.Matcher;
import org.jresearch.k8s.operator.istio.ingress.model.IngressAnnotation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.api.model.ServiceSpec;
import io.fabric8.kubernetes.api.model.ServiceSpecBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPath;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPathBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressRuleValue;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressRuleValueBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBackend;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBackendBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRule;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRuleBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressServiceBackend;
import io.fabric8.kubernetes.api.model.networking.v1.IngressServiceBackendBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressSpec;
import io.fabric8.kubernetes.api.model.networking.v1.IngressSpecBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressTLS;
import io.fabric8.kubernetes.api.model.networking.v1.IngressTLSBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.ServiceBackendPort;
import io.fabric8.kubernetes.api.model.networking.v1.ServiceBackendPortBuilder;
import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import io.fabric8.kubernetes.client.V1NetworkAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.NetworkAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.fabric8.kubernetes.client.utils.KubernetesResourceUtil;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.KubernetesTestServer;
import io.quarkus.test.kubernetes.client.WithKubernetesTestServer;
import lombok.AllArgsConstructor;
import lombok.Getter;
import me.snowdrop.istio.api.networking.v1beta1.Destination;
import me.snowdrop.istio.api.networking.v1beta1.Gateway;
import me.snowdrop.istio.api.networking.v1beta1.GatewayList;
import me.snowdrop.istio.api.networking.v1beta1.GatewaySpec;
import me.snowdrop.istio.api.networking.v1beta1.HTTPRoute;
import me.snowdrop.istio.api.networking.v1beta1.HTTPRouteDestination;
import me.snowdrop.istio.api.networking.v1beta1.Port;
import me.snowdrop.istio.api.networking.v1beta1.Server;
import me.snowdrop.istio.api.networking.v1beta1.ServerTLSSettingsMode;
import me.snowdrop.istio.api.networking.v1beta1.VirtualService;
import me.snowdrop.istio.api.networking.v1beta1.VirtualServiceList;
import me.snowdrop.istio.api.networking.v1beta1.VirtualServiceSpec;
import one.util.streamex.EntryStream;

@QuarkusTest
@WithKubernetesTestServer(port = 7890)
class IngressControllerTest {

	@KubernetesTestServer
	KubernetesServer mockServer;
	@Inject
	IngressController controller;

	private static final String TEST_NAMESPACE = "test";
	private static final String TEST_NAME = "test-ingress-istio";

	@SuppressWarnings("boxing")
	private static final Matcher<Port> MATCHER_PORT_HTTP = allOf(
		hasProperty("name", is("http")),
		hasProperty("number", is(80)),
		hasProperty("protocol", is("HTTP")));
	private static final Matcher<Port> MATCHER_TLS = allOf(
		hasProperty("credentialName", is("tls-example-com-tls")),
		hasProperty("mode", is(ServerTLSSettingsMode.SIMPLE)));
	@SuppressWarnings("boxing")
	private static final Matcher<Port> MATCHER_PORT_HTTPS = allOf(
		hasProperty("name", is("https")),
		hasProperty("number", is(443)),
		hasProperty("protocol", is("HTTPS")));

	private static final Map<String, String> EXPECTED_CUSTOM_SELECTOR = Map.of("app", "istio-ingressgateway", "istio", "ingressgateway");
	private static final Map<String, String> EXPECTED_DEFAULT_SELECTOR = Map.of("istio", "ingressgateway");

	// check predicates
	private static final Predicate<Object> TODO = t -> true;
	private static final Predicate<Object> EXIST = Objects::nonNull;
	private static final BiPredicate<HasMetadata, HasMetadata> OWNER = IngressControllerTest::checkOwner;
	private static final Predicate<HasMetadata> NAMESPACE = IngressControllerTest::checkNamespace;
	private static final Predicate<Gateway> TLS = IngressControllerTest::checkTls;
	private static final Predicate<Gateway> HTTP = IngressControllerTest::checkHttp;
	private static final Predicate<VirtualService> GW_NAME = IngressControllerTest::checkGwName;

	private static <I, W> BiPredicate<I, W> wrap(Predicate<? super W> resourcePredicate) {
		return (i, o) -> resourcePredicate.test(o);
	}

	@AfterEach
	void cleanUp() {
		try (NamespacedKubernetesClient client = mockServer.getClient()) {
			try (NetworkAPIGroupDSL network = client.network()) {
				try (V1NetworkAPIGroupDSL v1 = network.v1()) {
					assertEquals(Boolean.TRUE, v1.ingresses().inNamespace(TEST_NAMESPACE).delete());
					assertEquals(Boolean.TRUE, client.services().inNamespace(TEST_NAMESPACE).delete());
				}
			}
		}
	}

	@Getter
	@AllArgsConstructor
	private static enum GatevayParams {
		SET01(getIstioIngress(), EXIST),
		SET02(getIstioIngress(), OWNER),
		SET03(getIstioIngress(), NAMESPACE),
		SET04(getIstioIngress(), TODO),
		SET05(getIstioIngress(), TLS),
		SET06(getIngressWithRulesNoHttpDefault(), TLS),
		SET07(getIngressWithRulesHttpFalse(), TLS),
		SET08(getIngressWithRulesHttpTrue(), HTTP),
		SET09(getIngressWithIstioSelectorDefault(), gw -> checkSelector(gw, EXPECTED_DEFAULT_SELECTOR)),
		SET10(getIngressWithIstioSelectorSpecified(), gw -> checkSelector(gw, EXPECTED_CUSTOM_SELECTOR)),
		;

		private GatevayParams(Ingress testIngress, Predicate<? super Gateway> testGateway) {
			this(testIngress, wrap(testGateway));
		}

		private final Ingress testIngress;
		private final BiPredicate<? super Ingress, ? super Gateway> testGateway;
	}

	@ParameterizedTest
	@EnumSource
	@DisplayName("Should create Istio GW for provided ingress")
	void testGatewayCreate(GatevayParams testData) {

		var testIngressIstio = testData.getTestIngress();
		var testGateway = testData.getTestGateway();

		try (NamespacedKubernetesClient client = mockServer.getClient()) {
			try (NetworkAPIGroupDSL network = client.network()) {
				try (V1NetworkAPIGroupDSL v1 = network.v1()) {
					// Create and call controller
					v1.ingresses().create(testIngressIstio);
					controller.onAdd(testIngressIstio);
					assertNotNull(v1.ingresses().inNamespace(TEST_NAMESPACE).withName(TEST_NAME).get());

					// Check add
					await().until(() -> getGateway(client, TEST_NAME, TEST_NAMESPACE), gw -> testGateway.test(testIngressIstio, gw));
				}
			}
		}

	}

	@Getter
	@AllArgsConstructor
	@SuppressWarnings("unchecked")
	private static enum VirtualServiceParams {
		SET01("Should create the VS with desired name", getIngressWithRulesNoHttpDefault(), EXIST),
		SET02("Should have correct owner record pointed to test ingress", getIngressWithRulesNoHttpDefault(), OWNER),
		SET03("Should have the same NS as ingres", getIngressWithRulesNoHttpDefault(), NAMESPACE),
		SET04("should have correct gateway name", getIngressWithRulesNoHttpDefault(), GW_NAME),
		;

		private VirtualServiceParams(String testDescription, Ingress testIngress, Predicate<? super VirtualService> testVirtualService) {
			this(testDescription, testIngress, wrap(testVirtualService));
		}

		private VirtualServiceParams(String testDescription, Ingress testIngress, BiPredicate<? super Ingress, ? super VirtualService>... testVirtualServices) {
			this(testDescription, testIngress, List.of(testVirtualServices));
		}

		@Override
		public String toString() {
			return testDescription;
		}

		private final String testDescription;
		private final Ingress testIngress;
		private final List<BiPredicate<? super Ingress, ? super VirtualService>> testVirtualServices;
	}

	@SuppressWarnings({ "resource", "boxing" })
	@ParameterizedTest(name = "{0}")
	@EnumSource
	@DisplayName("Should create Istio GW and VS for provided ingress")
	void testVirtualServiceCreate(VirtualServiceParams testData) {

		var testIngressIstio = testData.getTestIngress();
		var testVirtualService = testData.getTestVirtualServices();

		try (NamespacedKubernetesClient client = mockServer.getClient()) {
			try (NetworkAPIGroupDSL network = client.network()) {
				try (V1NetworkAPIGroupDSL v1 = network.v1()) {
					// Create and call controller
					v1.ingresses().create(testIngressIstio);
					controller.onAdd(testIngressIstio);
					assertNotNull(v1.ingresses().inNamespace(TEST_NAMESPACE).withName(TEST_NAME).get());

					// Check add
					EntryStream.of(testVirtualService).forKeyValue((i, predicate) -> await().until(() -> getVirtualService(client, IngressController.genarateVirtualServiceName(TEST_NAME, i), TEST_NAMESPACE), vs -> predicate.test(testIngressIstio, vs)));
				}
			}
		}

	}

	@Getter
	@AllArgsConstructor
	@SuppressWarnings("unchecked")
	private static enum VirtualServicePortResolveParams {
		SET01("Ingress with named port and existing service with port", getIngressWithNamedPort(), getServiceWithPort(), IngressControllerTest::checkPort),
		SET02("Ingress with named port and nonexisting service", getIngressWithNamedPort(), null, IngressControllerTest::checkNoPort),
		SET03("Ingress with named port and existing service without port", getIngressWithNamedPort(), getServiceWithoutPort(), IngressControllerTest::checkNoPort),
		;

		private VirtualServicePortResolveParams(String testDescription, Ingress testIngress, Service testService, Predicate<? super VirtualService> testVirtualService) {
			this(testDescription, testIngress, testService, wrap(testVirtualService));
		}

		private VirtualServicePortResolveParams(String testDescription, Ingress testIngress, Service testService, BiPredicate<? super Ingress, ? super VirtualService>... testVirtualServices) {
			this(testDescription, testIngress, testService, List.of(testVirtualServices));
		}

		@Override
		public String toString() {
			return testDescription;
		}

		private final String testDescription;
		private final Ingress testIngress;
		private final Service testService;
		private final List<BiPredicate<? super Ingress, ? super VirtualService>> testVirtualServices;
	}

	@SuppressWarnings({ "resource", "boxing" })
	@ParameterizedTest(name = "{0}")
	@EnumSource
	@DisplayName("Should create Istio VS for provided ingress and resolve the port from service")
	void testVirtualServicePortResolve(VirtualServicePortResolveParams testData) {

		Ingress testIngressIstio = testData.getTestIngress();
		Service testService = testData.getTestService();
		List<BiPredicate<? super Ingress, ? super VirtualService>> testVirtualService = testData.getTestVirtualServices();

		try (NamespacedKubernetesClient client = mockServer.getClient()) {
			try (NetworkAPIGroupDSL network = client.network()) {
				try (V1NetworkAPIGroupDSL v1 = network.v1()) {
					// Create service
					if (testService != null) {
						client.services().create(testService);
						assertNotNull(client.services().inNamespace(TEST_NAMESPACE).withName(TEST_NAME).get());
					}
					// Create and call controller
					v1.ingresses().create(testIngressIstio);
					controller.onAdd(testIngressIstio);
					assertNotNull(v1.ingresses().inNamespace(TEST_NAMESPACE).withName(TEST_NAME).get());

					// Check add
					EntryStream.of(testVirtualService).forKeyValue((i, predicate) -> await().until(() -> getVirtualService(client, IngressController.genarateVirtualServiceName(TEST_NAME, i), TEST_NAMESPACE), vs -> predicate.test(testIngressIstio, vs)));
				}
			}
		}

	}

	@SuppressWarnings("boxing")
	@Test
	@DisplayName("Should ignore ingress with wrong IngressClassName")
	void testIgnoreIngress() {

		Ingress testIngressGeneral = getNonIstioIngress();

		try (NamespacedKubernetesClient client = mockServer.getClient()) {
			try (NetworkAPIGroupDSL network = client.network()) {
				try (V1NetworkAPIGroupDSL v1 = network.v1()) {
					// Create and call controller
					v1.ingresses().create(testIngressGeneral);
					controller.onAdd(testIngressGeneral);
					assertNotNull(v1.ingresses().inNamespace(TEST_NAMESPACE).withName(TEST_NAME).get());

					// Chek ignore general
					Resource<Gateway> testGatewayGeneral = client.resources(Gateway.class, GatewayList.class).inNamespace(TEST_NAMESPACE).withName(TEST_NAME);
					await().during(Duration.ofSeconds(1)).failFast(() -> EXIST.test(testGatewayGeneral)).until(() -> Boolean.TRUE);
					Resource<VirtualService> testVirtualServiceGeneral = client.resources(VirtualService.class, VirtualServiceList.class).inNamespace(TEST_NAMESPACE).withName(TEST_NAME);
					await().during(Duration.ofSeconds(1)).failFast(() -> EXIST.test(testVirtualServiceGeneral)).until(() -> Boolean.TRUE);
				}
			}
		}

	}

//	@Test
//	@DisplayName("REMOVE IT!!!!")
//	void testControllerT____to____remove____TODO() {
////		String testNamespace = "test";
////		String nameIstio = "test-ingress-istio";
////		String nameGeneral = "test-ingress-general";
//
//		Ingress testIngressIstio = getIstioIngress();
//
//		try (NamespacedKubernetesClient client = mockServer.getClient()) {
//
//			// IngressController controller = new IngressController(client);
//
//			try (NetworkAPIGroupDSL network = client.network()) {
//				try (V1NetworkAPIGroupDSL v1 = network.v1()) {
//					// update and call controller
//					// v1.ingresses().create(testIngress);
//					Ingress testNewIngress = new IngressBuilder(testIngressIstio)
//						.editMetadata()
//						.withResourceVersion("2")
//						.and()
//						.build();
//					controller.onUpdate(testIngressIstio, testNewIngress);
////					assertNotNull(v1.ingresses().inNamespace(testNamespace).withName(nameIstio).get());
//
//					// Check update
//					// Assertions.assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
//					// testGateway(client, testNamespace));
//					// Assertions.assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
//					// testVirtualService(client, testNamespace));
//
//					// Remove and call controller
//					v1.ingresses().delete(testIngressIstio);
//					controller.onDelete(testIngressIstio, false);
////					assertNull(v1.ingresses().inNamespace(testNamespace).withName(nameIstio).get());
//
//					// Check remove
//					// Assertions.assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
//					// testGateway(client, testNamespace));
//					// Assertions.assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
//					// testVirtualService(client, testNamespace));
//
//				}
//			}
//		}
//
//	}

	private static Service getServiceWithPort() {
		return getService(() -> createServiceSpec(() -> createServicePort()));
	}

	private static Service getServiceWithoutPort() {
		return getService(() -> createServiceSpec(() -> createAnotherServicePort()));
	}

	private static Service getService(Supplier<ServiceSpec> spec) {
		return new ServiceBuilder(true)
			.withMetadata(createMetadata(Map.of()))
			.withSpec(spec.get())
			.build();
	}

	private static ServiceSpec createServiceSpec(Supplier<List<ServicePort>> ports) {
		return new ServiceSpecBuilder(true)
			.withPorts(ports.get())
			.build();
	}

	private static List<ServicePort> createServicePort() {
		return List.of(new ServicePortBuilder(true)
			.withName("http")
			.withPort(80)
			.build());
	}

	private static List<ServicePort> createAnotherServicePort() {
		return List.of(new ServicePortBuilder(true)
			.withName("pgsql")
			.withPort(5432)
			.build());
	}

	private static Gateway getGateway(NamespacedKubernetesClient client, String name, String namespace) {
		return client.resources(Gateway.class, GatewayList.class).inNamespace(namespace).withName(name).get();
	}

	private static VirtualService getVirtualService(NamespacedKubernetesClient client, String name, String namespace) {
		return client.resources(VirtualService.class, VirtualServiceList.class).inNamespace(namespace).withName(name).get();
	}

	private static boolean checkOwner(HasMetadata owner, HasMetadata toCheck) {
		Optional<String> ownerUid = Optional.ofNullable(toCheck)
			.map(KubernetesResourceUtil::getControllerUid)
			.map(OwnerReference::getUid);
		return Optional.of(owner)
			.map(HasMetadata::getMetadata)
			.map(ObjectMeta::getUid)
			.equals(ownerUid);
	}

	private static boolean checkNamespace(HasMetadata toCheck) {
		return TEST_NAMESPACE.equals(Optional.of(toCheck)
			.map(HasMetadata::getMetadata)
			.map(ObjectMeta::getNamespace)
			.orElse(null));
	}

	private static boolean checkGwName(VirtualService toCheck) {
		return Optional.of(toCheck)
			.map(VirtualService::getSpec)
			.map(s -> s.getGateways())
			.orElseGet(List::of)
			.contains(TEST_NAME);
	}

	private static Ingress getIngressWithNamedPort() {
		return getIngress(Map.of(), () -> createSpec(IngressController.INGRESS_CLASSNAME, IngressControllerTest::createTls, IngressControllerTest::createRulesWithNamedPort));
	}

	private static Ingress getIngressWithRulesNoHttpDefault() {
		return getIngress(Map.of(), () -> createSpec(IngressController.INGRESS_CLASSNAME, IngressControllerTest::createTls, IngressControllerTest::createRulesWithNumberPort));
	}

	private static Ingress getIngressWithRulesHttpFalse() {
		return getIngress(Map.of(IngressAnnotation.ALLOW_HTTP.getName(), "false"), () -> createSpec(IngressController.INGRESS_CLASSNAME, IngressControllerTest::createTls, IngressControllerTest::createRulesWithNumberPort));
	}

	private static Ingress getIngressWithRulesHttpTrue() {
		return getIngress(Map.of(IngressAnnotation.ALLOW_HTTP.getName(), "true"), () -> createSpec(IngressController.INGRESS_CLASSNAME, IngressControllerTest::createTls, IngressControllerTest::createRulesWithNumberPort));
	}

	private static Ingress getIngressWithIstioSelectorDefault() {
		return getIngress(Map.of(), () -> createSpec(IngressController.INGRESS_CLASSNAME, IngressControllerTest::createTls, IngressControllerTest::createRulesWithNumberPort));
	}

	private static Ingress getIngressWithIstioSelectorSpecified() {
		return getIngress(Map.of(IngressAnnotation.ISTIO_SELECTOR.getName(), "app=istio-ingressgateway,istio=ingressgateway"), () -> createSpec(IngressController.INGRESS_CLASSNAME, IngressControllerTest::createTls, IngressControllerTest::createRulesWithNumberPort));
	}

	private static Ingress getNonIstioIngress() {
		return getIngress(Map.of(), () -> createSpec(null, List::of, List::of));
	}

	private static Ingress getIstioIngress() {
		return getIngress(Map.of(), () -> createSpec(IngressController.INGRESS_CLASSNAME, IngressControllerTest::createTls, List::of));
	}

	private static Ingress getIngress(Map<String, String> annotations, Supplier<IngressSpec> spec) {
		return new IngressBuilder()
			.withMetadata(createMetadata(annotations))
			.withSpec(spec.get())
			.build();
	}

	private static ObjectMeta createMetadata(Map<String, String> annotations) {
		return new ObjectMetaBuilder()
			.withName(TEST_NAME)
			.withNamespace(TEST_NAMESPACE)
			.withResourceVersion("1")
			.withAnnotations(annotations)
			.build();
	}

	private static IngressSpec createSpec(String ingressClassname, Supplier<List<IngressTLS>> tls, Supplier<List<IngressRule>> rule) {
		return new IngressSpecBuilder()
			.withIngressClassName(ingressClassname)
			.withTls(tls.get())
			.withRules(rule.get())
			.build();
	}

	private static List<IngressTLS> createTls() {
		return List.of(new IngressTLSBuilder()
			.withHosts("tls.example.com")
			.withSecretName("tls-example-com-tls")
			.build());
	}

	private static boolean checkTls(Gateway toCheck) {
		List<Server> servers = Optional.ofNullable(toCheck)
			.map(Gateway::getSpec)
			.map(GatewaySpec::getServers)
			.orElseGet(List::of);

		Matcher<Server> tlsServerMatcher = allOf(
			hasProperty("hosts", contains("tls.example.com")),
			hasProperty("port", MATCHER_PORT_HTTPS),
			hasProperty("tls", MATCHER_TLS));

		assertThat(servers, containsInAnyOrder(tlsServerMatcher));

		return true;
	}

	private static List<IngressRule> createRulesWithNamedPort() {
		return createRules(() -> createRuleValue(() -> createIngressPath(() -> createIngressBackend(() -> createIngressServiceBackend(IngressControllerTest::createServiceBackendNamedPort)))));
	}

	private static List<IngressRule> createRulesWithNumberPort() {
		return createRules(() -> createRuleValue(() -> createIngressPath(() -> createIngressBackend(() -> createIngressServiceBackend(IngressControllerTest::createServiceBackendNumberPort)))));
	}

	private static List<IngressRule> createRules(Supplier<HTTPIngressRuleValue> http) {
		return List.of(new IngressRuleBuilder()
			.withHost("http.example.com")
			.withHttp(http.get())
			.build());
	}

	private static HTTPIngressRuleValue createRuleValue(Supplier<HTTPIngressPath> path) {
		return new HTTPIngressRuleValueBuilder()
			.withPaths(path.get())
			.build();
	}

	private static HTTPIngressPath createIngressPath(Supplier<IngressBackend> backend) {
		return new HTTPIngressPathBuilder()
			.withPath("/path")
			.withPathType("ImplementationSpecific")
			.withBackend(backend.get())
			.build();
	}

	private static IngressBackend createIngressBackend(Supplier<IngressServiceBackend> service) {
		return new IngressBackendBuilder()
			.withService(service.get())
			.build();
	}

	private static IngressServiceBackend createIngressServiceBackend(Supplier<ServiceBackendPort> port) {
		return new IngressServiceBackendBuilder()
			.withName(TEST_NAME)
			.withPort(port.get())
			.build();
	}

	private static ServiceBackendPort createServiceBackendNumberPort() {
		return new ServiceBackendPortBuilder()
			.withNumber(80)
			.build();
	}

	private static ServiceBackendPort createServiceBackendNamedPort() {
		return new ServiceBackendPortBuilder()
			.withName("http")
			.build();
	}

	private static boolean checkHttp(Gateway toCheck) {
		List<Server> servers = Optional.ofNullable(toCheck)
			.map(Gateway::getSpec)
			.map(GatewaySpec::getServers)
			.orElseGet(List::of);

		Matcher<Server> tlsServerMatcher = allOf(
			hasProperty("hosts", contains("tls.example.com")),
			hasProperty("port", MATCHER_PORT_HTTPS),
			hasProperty("tls", MATCHER_TLS));

		Matcher<Server> ruleServerMatcher = allOf(
			hasProperty("hosts", contains("http.example.com")),
			hasProperty("port", MATCHER_PORT_HTTP));

		assertThat(servers, containsInAnyOrder(tlsServerMatcher, ruleServerMatcher));

		return true;
	}

	private static boolean checkSelector(Gateway toCheck, Map<String, String> expectedSelector) {
		var selector = Optional.ofNullable(toCheck)
			.map(Gateway::getSpec)
			.map(GatewaySpec::getSelector)
			.orElseGet(Map::of);

		assertThat(selector.entrySet(), everyItem(is(in(expectedSelector.entrySet()))));
		assertThat(expectedSelector.entrySet(), everyItem(is(in(selector.entrySet()))));

		return true;
	}

	private static boolean checkPort(VirtualService toCheck) {
		return checkPort(toCheck, hasProperty("number", is(80)));
	}

	private static boolean checkNoPort(VirtualService toCheck) {
		return checkPort(toCheck, nullValue());
	}

	private static boolean checkPort(VirtualService toCheck, Matcher<?> portMatcher) {
		List<HTTPRoute> routes = Optional.ofNullable(toCheck)
			.map(VirtualService::getSpec)
			.map(VirtualServiceSpec::getHttp)
			.orElseGet(List::of);

		Matcher<Destination> destinationMatcher = hasProperty("port", portMatcher);
		Matcher<HTTPRouteDestination> httpRouteDestinationMatcher = hasProperty("destination", destinationMatcher);
		Matcher<HTTPRoute> httpRouteMatcher = hasProperty("route", contains(httpRouteDestinationMatcher));

		assertThat(routes, contains(httpRouteMatcher));

		return true;
	}
}
