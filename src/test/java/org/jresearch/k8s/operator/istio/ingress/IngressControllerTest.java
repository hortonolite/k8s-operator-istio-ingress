package org.jresearch.k8s.operator.istio.ingress;

import static org.awaitility.Awaitility.*;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import javax.inject.Inject;

import org.awaitility.core.ConditionTimeoutException;
import org.hamcrest.Matcher;
import org.jresearch.k8s.operator.istio.ingress.model.IngressAnnotation;
import org.jresearch.k8s.operator.istio.ingress.model.PathType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.fabric8.istio.api.networking.v1beta1.Destination;
import io.fabric8.istio.api.networking.v1beta1.Gateway;
import io.fabric8.istio.api.networking.v1beta1.GatewayList;
import io.fabric8.istio.api.networking.v1beta1.GatewaySpec;
import io.fabric8.istio.api.networking.v1beta1.HTTPMatchRequest;
import io.fabric8.istio.api.networking.v1beta1.HTTPRoute;
import io.fabric8.istio.api.networking.v1beta1.HTTPRouteDestination;
import io.fabric8.istio.api.networking.v1beta1.IsStringMatchMatchType;
import io.fabric8.istio.api.networking.v1beta1.Port;
import io.fabric8.istio.api.networking.v1beta1.Server;
import io.fabric8.istio.api.networking.v1beta1.ServerTLSSettingsTLSmode;
import io.fabric8.istio.api.networking.v1beta1.StringMatch;
import io.fabric8.istio.api.networking.v1beta1.StringMatchExact;
import io.fabric8.istio.api.networking.v1beta1.StringMatchPrefix;
import io.fabric8.istio.api.networking.v1beta1.VirtualService;
import io.fabric8.istio.api.networking.v1beta1.VirtualServiceList;
import io.fabric8.istio.api.networking.v1beta1.VirtualServiceSpec;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.LoadBalancerIngress;
import io.fabric8.kubernetes.api.model.LoadBalancerIngressBuilder;
import io.fabric8.kubernetes.api.model.LoadBalancerStatus;
import io.fabric8.kubernetes.api.model.LoadBalancerStatusBuilder;
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
import io.fabric8.kubernetes.api.model.networking.v1.IngressStatus;
import io.fabric8.kubernetes.api.model.networking.v1.IngressStatusBuilder;
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
import one.util.streamex.EntryStream;
import one.util.streamex.StreamEx;

@QuarkusTest
@WithKubernetesTestServer(port = 7890)
class IngressControllerTest {

	@KubernetesTestServer
	KubernetesServer mockServer;
	@Inject
	IngressController controller;

	private static final String CUSTOM_SELECTOR = "app=istio-ingressgateway,istio=ingressgateway";
	private static final String TEST_NAMESPACE = "test";
	private static final String ISTIO_NAMESPACE = "istio";
	private static final String TEST_NAME = "test-ingress-istio";
	private static final String STATUS_IP_01 = "1.1.1.1";
	private static final String STATUS_IP_09 = "9.9.9.9";

	@SuppressWarnings("boxing")
	private static final Matcher<Port> MATCHER_PORT_HTTP = allOf(
		hasProperty("name", is("http")),
		hasProperty("number", is(80)),
		hasProperty("protocol", is("HTTP")));
	private static final Function<String, Matcher<Port>> MATCHER_TLS = name -> allOf(
		hasProperty("credentialName", is(name)),
		hasProperty("mode", is(ServerTLSSettingsTLSmode.SIMPLE)));
	@SuppressWarnings("boxing")
	private static final Matcher<Port> MATCHER_PORT_HTTPS = allOf(
		hasProperty("name", is("https")),
		hasProperty("number", is(443)),
		hasProperty("protocol", is("HTTPS")));
	private static final BiFunction<String, String, Matcher<Server>> TLS_SERVER_MATCHER = (host, secret) -> allOf(
		hasProperty("hosts", contains(host)),
		hasProperty("port", MATCHER_PORT_HTTPS),
		hasProperty("tls", MATCHER_TLS.apply(secret)));
	private static final Function<String, Matcher<Server>> RULE_SERVER_MATCHER = host -> allOf(
		hasProperty("hosts", contains(host)),
		hasProperty("port", MATCHER_PORT_HTTP));

	private static final Map<String, String> EXPECTED_CUSTOM_SELECTOR = Map.of("app", "istio-ingressgateway", "istio", "ingressgateway");
	private static final Map<String, String> EXPECTED_DEFAULT_SELECTOR = Map.of("istio", "ingressgateway");

	// check predicates
//	private static final Predicate<Object> TODO = t -> true;
	private static final Predicate<Object> EXIST = Objects::nonNull;
	private static final BiPredicate<HasMetadata, HasMetadata> OWNER = IngressControllerTest::checkOwner;
	private static final Predicate<HasMetadata> NAMESPACE = IngressControllerTest::checkNamespace;
	private static final Predicate<Gateway> TLS = IngressControllerTest::checkTlsOnly;
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
					assertEquals(Boolean.TRUE, client.resources(VirtualService.class, VirtualServiceList.class).inNamespace(TEST_NAMESPACE).delete());
					assertEquals(Boolean.TRUE, client.resources(Gateway.class, GatewayList.class).inNamespace(TEST_NAMESPACE).delete());
					assertEquals(Boolean.TRUE, v1.ingresses().inNamespace(TEST_NAMESPACE).delete());
					assertEquals(Boolean.TRUE, client.services().inNamespace(TEST_NAMESPACE).delete());
					assertEquals(Boolean.TRUE, client.services().inNamespace(ISTIO_NAMESPACE).delete());
				}
			}
		}
	}

	@Getter
	@AllArgsConstructor
	private static enum CreateGatevayParams {
		SET01("Should create the GW with desired name", getIstioIngress(), EXIST),
		SET02("Should have correct owner record pointed to test ingress", getIstioIngress(), OWNER),
		SET03("Should have the same NS as ingress", getIstioIngress(), NAMESPACE),
		SET04("Should have correct TLS section for Ingress with TLS", getIstioIngress(), TLS),
		SET05("Should have correct TLS section for Ingress without HTTPS anotation", getIngressWithRulesNoHttpDefault(), TLS),
		SET06("Should have correct TLS section for Ingress with HTTPS anotation false", getIngressWithRulesHttpFalse(), TLS),
		SET07("Should have correct TLS and host sections for Ingress with HTTPS anotation true", getIngressWithRulesHttpTrue(), IngressControllerTest::checkHttp),
		SET08("Check default selector to Istio ingress", getIngressWithIstioSelectorDefault(), gw -> checkSelector(gw, EXPECTED_DEFAULT_SELECTOR)),
		SET09("Check custom selector to Istio ingress", getIngressWithIstioCustomSelector(), gw -> checkSelector(gw, EXPECTED_CUSTOM_SELECTOR)),;

		private CreateGatevayParams(String testDescription, Ingress testIngress, Predicate<? super Gateway> testGateway) {
			this(testDescription, testIngress, wrap(testGateway));
		}

		@Override
		public String toString() {
			return testDescription;
		}

		private final String testDescription;
		private final Ingress testIngress;
		private final BiPredicate<? super Ingress, ? super Gateway> testGateway;
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource
	@DisplayName("Should create Istio GW for provided ingress")
	void testGatewayCreate(CreateGatevayParams testData) {

		Ingress testIngressIstio = testData.getTestIngress();
		BiPredicate<? super Ingress, ? super Gateway> testGateway = testData.getTestGateway();

		try (NamespacedKubernetesClient client = mockServer.getClient()) {
			try (NetworkAPIGroupDSL network = client.network()) {
				try (V1NetworkAPIGroupDSL v1 = network.v1()) {
					// Create and call controller
					Ingress ingressV1 = v1.ingresses().create(testIngressIstio);
					controller.onAdd(ingressV1);
					assertNotNull(v1.ingresses().inNamespace(TEST_NAMESPACE).withName(TEST_NAME).get());

					// Check add
					await().until(() -> getGateway(client, TEST_NAME, TEST_NAMESPACE), gw -> testGateway.test(ingressV1, gw.orElse(null)));
				}
			}
		}

	}

	@Getter
	@AllArgsConstructor
	private static enum UpdateGatevayParams {
		// Untracked ingress + istio -> non istio
		UNTRACKED01("On change untracked Ingress parameters GW still the same", getIstioIngress(), IngressControllerTest::changeDescription, Object::equals),
		UNTRACKED02("On change ingressClass to istio new GW is generated", getNonIstioIngress(), IngressControllerTest::addIngressClass, IngressControllerTest::checkCreated),
		UNTRACKED03("On change ingressClass to non istio GW is removed", getIstioIngress(), IngressControllerTest::changeIngressClass, IngressControllerTest::checkRemoved),
		UNTRACKED04("On remove ingressClass GW is removed", getIstioIngress(), IngressControllerTest::removeIngressClass, IngressControllerTest::checkRemoved),
		// httpOnly annotation
		HTTP_ONLY01("On add httpOnly false annotation GW hasn't the mapping for HTTP port", getIstioIngress(), IngressControllerTest::addHttpFalse, IngressControllerTest::checkNo2NoHttp),
		HTTP_ONLY02("On add httpOnly true annotation GW has the mapping for HTTP port", getIstioIngress(), IngressControllerTest::addHttpTrue, IngressControllerTest::checkNo2YesHttp),
		HTTP_ONLY03("On change httpOnly true -> false annotation GW hasn't the mapping for HTTP port", getIngressWithRulesHttpTrue(), IngressControllerTest::addHttpFalse, IngressControllerTest::checkYes2NoHttp),
		HTTP_ONLY04("On change httpOnly false -> true annotation GW has the mapping for HTTP port", getIngressWithRulesHttpFalse(), IngressControllerTest::addHttpTrue, IngressControllerTest::checkNo2YesHttp),
		HTTP_ONLY05("On remove httpOnly true annotation GW hasn't the mapping for HTTP port", getIngressWithRulesHttpTrue(), IngressControllerTest::removeAnnotations, IngressControllerTest::checkYes2NoHttp),
		HTTP_ONLY06("On remove httpOnly false annotation new GW hasn't the mapping for HTTP port", getIngressWithRulesHttpFalse(), IngressControllerTest::removeAnnotations, IngressControllerTest::checkNo2NoHttp),
		// istio selector annotation
		SELECTOR01("On add istio selector annotation with default selector GW should have default selector", getIstioIngress(), IngressControllerTest::addDefaultSelector, IngressControllerTest::checkDefaul2DefaultSelector),
		SELECTOR02("On add istio selector annotation with custom selector GW should have custom selector", getIstioIngress(), IngressControllerTest::addCustomSelector, IngressControllerTest::checkDefault2CustomSelector),
		SELECTOR03("On change istio selector annotation GW should have updated selector", getIngressWithIstioCustomSelector(), IngressControllerTest::addDefaultSelector, IngressControllerTest::checkCustom2DefaultSelector),
		SELECTOR04("On remove istio selector annotation GW should have default selector", getIngressWithIstioCustomSelector(), IngressControllerTest::removeAnnotations, IngressControllerTest::checkCustom2DefaultSelector),
		// TLS
		TLS01("On add new ingress TLS GW should update tls list", getIstioIngress(), IngressControllerTest::addNewTls, IngressControllerTest::checkOne2TwoTls),
		TLS02("On change ingress TLS GW should upate existing record", getIstioIngress(), IngressControllerTest::updateTlsHost, IngressControllerTest::checkNewHostTls),
		TLS03("On change ingress TLS GW should upate existing record", getIstioIngress(), IngressControllerTest::updateTlsSecret, IngressControllerTest::checkNewSecretTls),
		TLS04("On change ingress TLS GW should upate existing record", getIstioIngress(), IngressControllerTest::updateTlsBoth, IngressControllerTest::checkNewBothTls),
		TLS05("On remove ingres TLS GW should update tls list", getIstioIngressWithTwoTls(), IngressControllerTest::removeTls, IngressControllerTest::checkTwo2OneTls),

		;

		private UpdateGatevayParams(String testDescription, Ingress testIngress, UnaryOperator<Ingress> ingressModificator, ChangePredicate<? super Gateway> testGateway) {
			this(testDescription, testIngress, ingressModificator, ChangeBiPredicate.wrap(testGateway));
		}

		@Override
		public String toString() {
			return testDescription;
		}

		private final String testDescription;
		private final Ingress testIngress;
		private final UnaryOperator<Ingress> ingressModificator;
		private final ChangeBiPredicate<? super Ingress, ? super Gateway> testGateway;
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource
	@DisplayName("Should update Istio GW on ingress update")
	void testGatewayUpdate(UpdateGatevayParams testData) {

		Ingress testIngressIstio = testData.getTestIngress();
		UnaryOperator<Ingress> ingressModificator = testData.getIngressModificator();
		ChangeBiPredicate<? super Ingress, ? super Gateway> testGateway = testData.getTestGateway();

		try (NamespacedKubernetesClient client = mockServer.getClient()) {
			try (NetworkAPIGroupDSL network = client.network()) {
				try (V1NetworkAPIGroupDSL v1 = network.v1()) {
					// Create and call controller
					Ingress ingressV1 = v1.ingresses().create(testIngressIstio);
					assertNotNull(v1.ingresses().inNamespace(TEST_NAMESPACE).withName(TEST_NAME).get());
					controller.onAdd(ingressV1);
					// Check add
					try {
						await().timeout(Duration.ofSeconds(2)).until(() -> getGateway(client, TEST_NAME, TEST_NAMESPACE), Optional::isPresent);
					} catch (ConditionTimeoutException e) {
						// May not exists - it is Ok
					}
					Gateway gatewayV1 = client.resources(Gateway.class, GatewayList.class).inNamespace(TEST_NAMESPACE).withName(TEST_NAME).get();

					Ingress ingressV2 = v1.ingresses().patch(ingressModificator.apply(ingressV1));
					assertNotNull(v1.ingresses().inNamespace(TEST_NAMESPACE).withName(TEST_NAME).get());
					controller.onUpdate(ingressV1, ingressV2);

					// Check update
					await().until(() -> getGateway(client, TEST_NAME, TEST_NAMESPACE), gw -> testGateway.test(ingressV1, gatewayV1, ingressV2, gw.orElse(null)));
				}
			}
		}

	}

	@Getter
	@AllArgsConstructor
	@DisplayName("Should update ingress status")
	private static enum UpdateIngressParams {
		// Update ingress status
		STATUS01("On change ingressClass to istio status removed if there is no Istio ingress with ExternalIP", getNonIstioIngress(), IngressControllerTest::addIngressClass, IngressControllerTest::checkNoStatus),
		STATUS02("On change ingressClass to istio status updated if there is Istio ingress with ExternalIP", getNonIstioIngressWithIstioCustomSelector(), IngressControllerTest::addIngressClass, IngressControllerTest::checkStatus),
		STATUS03("On change istio selector annotation status status removed if there is no Istio ingress with ExternalIP", getIngressWithIstioCustomSelector(), IngressControllerTest::addDefaultSelector, IngressControllerTest::checkNoStatus),
		STATUS04("On change istio selector annotation status updated if there is Istio ingress with ExternalIP", getIstioIngress(), IngressControllerTest::addCustomSelector, IngressControllerTest::checkStatus),
		STATUS05("On remove ingressClass status removed", getIstioIngress(), IngressControllerTest::removeIngressClass, IngressControllerTest::checkNoStatus),
		;

		@Override
		public String toString() {
			return testDescription;
		}

		private final String testDescription;
		private final Ingress testIngress;
		private final UnaryOperator<Ingress> ingressModificator;
		private final Predicate<? super Ingress> test;
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource
	void testIngressUpdate(UpdateIngressParams testData) {

		Ingress testIngressIstio = testData.getTestIngress();
		UnaryOperator<Ingress> ingressModificator = testData.getIngressModificator();
		Predicate<? super Ingress> testIngress = testData.getTest();

		try (NamespacedKubernetesClient client = mockServer.getClient()) {
			creteIstioIngressController(client, "istio_ingress_01", ISTIO_NAMESPACE, EXPECTED_DEFAULT_SELECTOR, "");
			creteIstioIngressController(client, "istio_ingress_02", ISTIO_NAMESPACE, EXPECTED_CUSTOM_SELECTOR, STATUS_IP_01);
			try (NetworkAPIGroupDSL network = client.network()) {
				try (V1NetworkAPIGroupDSL v1 = network.v1()) {
					// Create and call controller
					Ingress ingressV1 = v1.ingresses().create(testIngressIstio);
					assertNotNull(v1.ingresses().inNamespace(TEST_NAMESPACE).withName(TEST_NAME).get());
					controller.onAdd(ingressV1);
					// Check add
					try {
						await().timeout(Duration.ofSeconds(2)).until(() -> getGateway(client, TEST_NAME, TEST_NAMESPACE), Optional::isPresent);
					} catch (ConditionTimeoutException e) {
						// May not exists - it is Ok
					}

					Ingress ingressV2 = v1.ingresses().patch(ingressModificator.apply(ingressV1));
					assertNotNull(v1.ingresses().inNamespace(TEST_NAMESPACE).withName(TEST_NAME).get());
					controller.onUpdate(ingressV1, ingressV2);

					// Check update
					await().until(() -> getIngress(v1, TEST_NAME, TEST_NAMESPACE), ingress -> testIngress.test(ingress.orElse(null)));
				}
			}
		}

	}

	private static void creteIstioIngressController(NamespacedKubernetesClient client, String name, String namespace, Map<String, String> labels, String ip) {
		client.services().inNamespace(namespace).create(creteService(name, namespace, labels, ip));
	}

	private static Service creteService(String name, String namespace, Map<String, String> labels, String ip) {
		return new ServiceBuilder()
			.withMetadata(createMetadata(name, namespace, Map.of(), labels))
			.withSpec(ip.isBlank() ? null : createSpec(ip))
			.build();
	}

	private static ServiceSpec createSpec(String ip) {
		return new ServiceSpecBuilder()
			.withExternalIPs(ip)
			.build();
	}

	@Getter
	@AllArgsConstructor
	@SuppressWarnings("unchecked")
	private static enum VirtualServiceParams {
		SET01("Should create the VS with desired name", getIngressWithRulesNoHttpDefault(), EXIST),
		SET02("Should have correct owner record pointed to test ingress", getIngressWithRulesNoHttpDefault(), OWNER),
		SET03("Should have the same NS as ingress", getIngressWithRulesNoHttpDefault(), NAMESPACE),
		SET04("should have correct gateway name", getIngressWithRulesNoHttpDefault(), GW_NAME),
		SET05("should have prefix path for ImplementationSpecific ingress", getIngressWithImplementationSpecificPath(), IngressControllerTest::checkPrefixPath),
		SET06("should have prefix path for Prefix ingress", getIngressWithPrefixPath(), IngressControllerTest::checkPrefixPath),
		SET07("should have exact path for Exact ingress", getIngressWithExactPath(), IngressControllerTest::checkExactPath),
		SET08("should generate 2 VS for ingress with 2 rules", getIngressWithTwoRules(), vs -> checkHost(vs, "www.example.com"), vs -> checkHost(vs, "http.example.com")),;

		@SuppressWarnings("resource")
		private VirtualServiceParams(String testDescription, Ingress testIngress, Predicate<? super VirtualService>... testVirtualServices) {
			this(testDescription, testIngress, StreamEx.of(testVirtualServices).<BiPredicate<? super Ingress, ? super VirtualService>>map(IngressControllerTest::wrap).toList());
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
	@DisplayName("Should create correct Istio VS for provided ingress")
	void testVirtualServiceCreate(VirtualServiceParams testData) {

		var testIngressIstio = testData.getTestIngress();
		var testVirtualService = testData.getTestVirtualServices();

		try (NamespacedKubernetesClient client = mockServer.getClient()) {
			try (NetworkAPIGroupDSL network = client.network()) {
				try (V1NetworkAPIGroupDSL v1 = network.v1()) {
					// Create and call controller
					Ingress ingressV1 = v1.ingresses().create(testIngressIstio);
					controller.onAdd(ingressV1);
					assertNotNull(v1.ingresses().inNamespace(TEST_NAMESPACE).withName(TEST_NAME).get());

					// Check add
					EntryStream.of(testVirtualService).forKeyValue((i, predicate) -> await().until(() -> getVirtualService(client, IngressController.genarateVirtualServiceName(TEST_NAME, i), TEST_NAMESPACE), vs -> predicate.test(ingressV1, vs)));
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
		SET03("Ingress with named port and existing service without port", getIngressWithNamedPort(), getServiceWithoutPort(), IngressControllerTest::checkNoPort),;

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
					Ingress ingressV1 = v1.ingresses().create(testIngressIstio);
					controller.onAdd(ingressV1);
					assertNotNull(v1.ingresses().inNamespace(TEST_NAMESPACE).withName(TEST_NAME).get());

					// Check add
					EntryStream.of(testVirtualService).forKeyValue((i, predicate) -> await().until(() -> getVirtualService(client, IngressController.genarateVirtualServiceName(TEST_NAME, i), TEST_NAMESPACE), vs -> predicate.test(ingressV1, vs)));
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
					Ingress ingress = v1.ingresses().create(testIngressGeneral);
					controller.onAdd(ingress);
					assertNotNull(v1.ingresses().inNamespace(TEST_NAMESPACE).withName(TEST_NAME).get());

					// Chek ignore general
					Resource<Gateway> testGatewayGeneral = client.resources(Gateway.class, GatewayList.class).inNamespace(TEST_NAMESPACE).withName(TEST_NAME);
					await().during(Duration.ofSeconds(1)).failFast(() -> testGatewayGeneral.get() != null).until(() -> Boolean.TRUE);
					Resource<VirtualService> testVirtualServiceGeneral = client.resources(VirtualService.class, VirtualServiceList.class).inNamespace(TEST_NAMESPACE).withName(TEST_NAME);
					await().during(Duration.ofSeconds(1)).failFast(() -> testVirtualServiceGeneral.get() != null).until(() -> Boolean.TRUE);
				}
			}
		}

	}

	private static Service getServiceWithPort() {
		return getService(() -> createServiceSpec(() -> createServicePort()));
	}

	private static Service getServiceWithoutPort() {
		return getService(() -> createServiceSpec(() -> createAnotherServicePort()));
	}

	@SuppressWarnings("boxing")
	private static Service getService(Supplier<ServiceSpec> spec) {
		return new ServiceBuilder(true)
			.withMetadata(createMetadata(Map.of()))
			.withSpec(spec.get())
			.build();
	}

	@SuppressWarnings("boxing")
	private static ServiceSpec createServiceSpec(Supplier<List<ServicePort>> ports) {
		return new ServiceSpecBuilder(true)
			.withPorts(ports.get())
			.build();
	}

	@SuppressWarnings("boxing")
	private static List<ServicePort> createServicePort() {
		return List.of(new ServicePortBuilder(true)
			.withName("http")
			.withPort(80)
			.build());
	}

	@SuppressWarnings("boxing")
	private static List<ServicePort> createAnotherServicePort() {
		return List.of(new ServicePortBuilder(true)
			.withName("pgsql")
			.withPort(5432)
			.build());
	}

	private static Optional<Ingress> getIngress(V1NetworkAPIGroupDSL v1, String name, String namespace) {
		return Optional.ofNullable(v1.ingresses().inNamespace(namespace).withName(name).get());
	}

	private static Optional<Gateway> getGateway(NamespacedKubernetesClient client, String name, String namespace) {
		return Optional.ofNullable(client.resources(Gateway.class, GatewayList.class).inNamespace(namespace).withName(name).get());
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

	private static Ingress getIngressWithTwoRules() {
		return getIngress(Map.of(), () -> createSpec(IngressController.INGRESS_CLASSNAME, IngressControllerTest::createTls, IngressControllerTest::createTwoRules), STATUS_IP_09);
	}

	private static Ingress getIngressWithImplementationSpecificPath() {
		return getIngress(Map.of(), () -> createSpec(IngressController.INGRESS_CLASSNAME, IngressControllerTest::createTls, IngressControllerTest::createRulesWithImplementationSpecificPath), STATUS_IP_09);
	}

	private static Ingress getIngressWithPrefixPath() {
		return getIngress(Map.of(), () -> createSpec(IngressController.INGRESS_CLASSNAME, IngressControllerTest::createTls, IngressControllerTest::createRulesWithPrefixPath), STATUS_IP_09);
	}

	private static Ingress getIngressWithExactPath() {
		return getIngress(Map.of(), () -> createSpec(IngressController.INGRESS_CLASSNAME, IngressControllerTest::createTls, IngressControllerTest::createRulesWithExactPath), STATUS_IP_09);
	}

	private static Ingress getIngressWithNamedPort() {
		return getIngress(Map.of(), () -> createSpec(IngressController.INGRESS_CLASSNAME, IngressControllerTest::createTls, IngressControllerTest::createRulesWithNamedPort), STATUS_IP_09);
	}

	private static Ingress getIngressWithRulesNoHttpDefault() {
		return getIngress(Map.of(), () -> createSpec(IngressController.INGRESS_CLASSNAME, IngressControllerTest::createTls, IngressControllerTest::createRulesWithNumberPort), STATUS_IP_09);
	}

	private static Ingress getIngressWithRulesHttpFalse() {
		return getIngress(Map.of(IngressAnnotation.ALLOW_HTTP.getName(), "false"), () -> createSpec(IngressController.INGRESS_CLASSNAME, IngressControllerTest::createTls, IngressControllerTest::createRulesWithNumberPort), STATUS_IP_09);
	}

	private static Ingress getIngressWithRulesHttpTrue() {
		return getIngress(Map.of(IngressAnnotation.ALLOW_HTTP.getName(), "true"), () -> createSpec(IngressController.INGRESS_CLASSNAME, IngressControllerTest::createTls, IngressControllerTest::createRulesWithNumberPort), STATUS_IP_09);
	}

	private static Ingress getIngressWithIstioSelectorDefault() {
		return getIngress(Map.of(), () -> createSpec(IngressController.INGRESS_CLASSNAME, IngressControllerTest::createTls, IngressControllerTest::createRulesWithNumberPort), STATUS_IP_09);
	}

	private static Ingress getIngressWithIstioCustomSelector() {
		return getIngress(Map.of(IngressAnnotation.ISTIO_SELECTOR.getName(), CUSTOM_SELECTOR), () -> createSpec(IngressController.INGRESS_CLASSNAME, IngressControllerTest::createTls, IngressControllerTest::createRulesWithNumberPort), STATUS_IP_09);
	}

	private static Ingress getNonIstioIngress() {
		return getIngress(Map.of(), () -> createSpec(null, List::of, List::of), STATUS_IP_09);
	}

	private static Ingress getNonIstioIngressWithIstioCustomSelector() {
		return getIngress(Map.of(IngressAnnotation.ISTIO_SELECTOR.getName(), CUSTOM_SELECTOR), () -> createSpec(null, List::of, List::of), STATUS_IP_09);
	}

	private static Ingress getIstioIngress() {
		return getIngress(Map.of(), () -> createSpec(IngressController.INGRESS_CLASSNAME, IngressControllerTest::createTls, IngressControllerTest::createRulesWithNumberPort), STATUS_IP_09);
	}

	private static Ingress getIstioIngressWithTwoTls() {
		return getIngress(Map.of(), () -> createSpec(IngressController.INGRESS_CLASSNAME, IngressControllerTest::createTwoTls, IngressControllerTest::createRulesWithNumberPort), STATUS_IP_09);
	}

	private static Ingress getIngress(Map<String, String> annotations, Supplier<IngressSpec> spec, String ip) {
		return new IngressBuilder()
			.withMetadata(createMetadata(annotations))
			.withSpec(spec.get())
			.withStatus(createStatus(ip))
			.build();
	}

	private static IngressStatus createStatus(String ip) {
		return new IngressStatusBuilder()
			.withLoadBalancer(createLoadBalancerStatus(List.of(ip)))
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
			.map(IngressControllerTest::createLoadBalancerIngress)
			.toList();
	}

	private static LoadBalancerIngress createLoadBalancerIngress(String istioIngressIp) {
		return new LoadBalancerIngressBuilder()
			.withIp(istioIngressIp)
			.build();
	}

	private static ObjectMeta createMetadata(Map<String, String> annotations) {
		return createMetadata(TEST_NAME, TEST_NAMESPACE, annotations, Map.of());
	}

	private static ObjectMeta createMetadata(String name, String namespace, Map<String, String> annotations, Map<String, String> labels) {
		return new ObjectMetaBuilder()
			.withName(name)
			.withNamespace(namespace)
			.withResourceVersion("1")
			.withAnnotations(annotations)
			.withLabels(labels)
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
		return List.of(createTls("www.example.com", "www-example-com-tls"));
	}

	private static List<IngressTLS> createTwoTls() {
		return List.of(
			createTls("www.example.com", "www-example-com-tls"),
			createTls("tls.example.com", "tls-example-com-tls"));
	}

	private static IngressTLS createTls(String host, String secret) {
		return new IngressTLSBuilder()
			.withHosts(host)
			.withSecretName(secret)
			.build();
	}

	private static boolean checkTlsOnly(Gateway toCheck) {
		List<Server> servers = Optional.ofNullable(toCheck)
			.map(Gateway::getSpec)
			.map(GatewaySpec::getServers)
			.orElseGet(List::of);

		assertThat(servers, containsInAnyOrder(TLS_SERVER_MATCHER.apply("www.example.com", "www-example-com-tls")));

		return true;
	}

	private static List<IngressRule> createTwoRules() {
		return createTwoRules(() -> createRuleValue(() -> createIngressPath(() -> createIngressBackend(() -> createIngressServiceBackend(IngressControllerTest::createServiceBackendNumberPort)), PathType.IMPLEMENTATION_SPECIFIC)));
	}

	private static List<IngressRule> createRulesWithImplementationSpecificPath() {
		return createRules(() -> createRuleValue(() -> createIngressPath(() -> createIngressBackend(() -> createIngressServiceBackend(IngressControllerTest::createServiceBackendNumberPort)), PathType.IMPLEMENTATION_SPECIFIC)));
	}

	private static List<IngressRule> createRulesWithExactPath() {
		return createRules(() -> createRuleValue(() -> createIngressPath(() -> createIngressBackend(() -> createIngressServiceBackend(IngressControllerTest::createServiceBackendNumberPort)), PathType.EXACT)));
	}

	private static List<IngressRule> createRulesWithPrefixPath() {
		return createRules(() -> createRuleValue(() -> createIngressPath(() -> createIngressBackend(() -> createIngressServiceBackend(IngressControllerTest::createServiceBackendNumberPort)), PathType.PREFIX)));
	}

	private static List<IngressRule> createRulesWithNamedPort() {
		return createRules(() -> createRuleValue(() -> createIngressPath(() -> createIngressBackend(() -> createIngressServiceBackend(IngressControllerTest::createServiceBackendNamedPort)), PathType.IMPLEMENTATION_SPECIFIC)));
	}

	private static List<IngressRule> createRulesWithNumberPort() {
		return createRules(() -> createRuleValue(() -> createIngressPath(() -> createIngressBackend(() -> createIngressServiceBackend(IngressControllerTest::createServiceBackendNumberPort)), PathType.IMPLEMENTATION_SPECIFIC)));
	}

	private static List<IngressRule> createRules(Supplier<HTTPIngressRuleValue> http) {
		return List.of(createRule("www.example.com", http));
	}

	private static List<IngressRule> createTwoRules(Supplier<HTTPIngressRuleValue> http) {
		return List.of(createRule("www.example.com", http), createRule("http.example.com", http));
	}

	private static IngressRule createRule(String host, Supplier<HTTPIngressRuleValue> http) {
		return new IngressRuleBuilder()
			.withHost(host)
			.withHttp(http.get())
			.build();
	}

	private static HTTPIngressRuleValue createRuleValue(Supplier<HTTPIngressPath> path) {
		return new HTTPIngressRuleValueBuilder()
			.withPaths(path.get())
			.build();
	}

	private static HTTPIngressPath createIngressPath(Supplier<IngressBackend> backend, PathType pathType) {
		return new HTTPIngressPathBuilder()
			.withPath("/path")
			.withPathType(pathType.getType())
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

	@SuppressWarnings("boxing")
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

		assertThat(servers, containsInAnyOrder(TLS_SERVER_MATCHER.apply("www.example.com", "www-example-com-tls"), RULE_SERVER_MATCHER.apply("www.example.com")));

		return true;
	}

	private static boolean checkDefaultSelector(Gateway toCheck) {
		return checkSelector(toCheck, EXPECTED_DEFAULT_SELECTOR);
	}

	private static boolean checkCustomSelector(Gateway toCheck) {
		return checkSelector(toCheck, EXPECTED_CUSTOM_SELECTOR);
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

	@SuppressWarnings("boxing")
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

//		routes.get(0).getRoute().get(0).getDestination().getPort().getNumber()

		Matcher<Destination> destinationMatcher = hasProperty("port", portMatcher);
		Matcher<HTTPRouteDestination> httpRouteDestinationMatcher = hasProperty("destination", destinationMatcher);
		Matcher<HTTPRoute> httpRouteMatcher = hasProperty("route", contains(httpRouteDestinationMatcher));

		assertThat(routes, contains(httpRouteMatcher));

		return true;
	}

	private static boolean checkHost(VirtualService toCheck, String host) {
		List<String> routes = Optional.ofNullable(toCheck)
			.map(VirtualService::getSpec)
			.map(VirtualServiceSpec::getHosts)
			.orElseGet(List::of);

		assertThat(routes, contains(host));

		return true;
	}

	private static boolean checkExactPath(VirtualService toCheck) {
		return checkPathType(toCheck, StringMatchExact.class);
	}

	private static boolean checkPrefixPath(VirtualService toCheck) {
		return checkPathType(toCheck, StringMatchPrefix.class);
	}

	private static boolean checkPathType(VirtualService toCheck, Class<? extends IsStringMatchMatchType> matchTypeClass) {
		List<HTTPRoute> routes = Optional.ofNullable(toCheck)
			.map(VirtualService::getSpec)
			.map(VirtualServiceSpec::getHttp)
			.orElseGet(List::of);

//		routes.get(0).getMatch().get(0).getUri().getMatchType()

		Matcher<Object> stringPrefixMatchMatcher = hasProperty("prefix", is("/path"));
		Matcher<Object> stringExactMatchMatcher = hasProperty("exact", is("/path"));
		Matcher<StringMatch> stringMatchMatcher = hasProperty("matchType", allOf(instanceOf(matchTypeClass), anyOf(stringPrefixMatchMatcher, stringExactMatchMatcher)));
		Matcher<HTTPMatchRequest> httpMatchRequestMatcher = hasProperty("uri", stringMatchMatcher);
		Matcher<HTTPRoute> httpRouteMatcher = hasProperty("match", contains(httpMatchRequestMatcher));

		assertThat(routes, contains(httpRouteMatcher));

		return true;
	}

	private static Ingress changeDescription(Ingress v1) {
		return new IngressBuilder(v1)
			.editMetadata()
			.addToLabels("newLabel", "someValueOfNewLabel")
			.endMetadata()
			.build();
	}

	private static Ingress addIngressClass(Ingress v1) {
		return editIngressClass(v1, IngressController.INGRESS_CLASSNAME);
	}

	private static Ingress changeIngressClass(Ingress v1) {
		return editIngressClass(v1, "non-istio");
	}

	private static Ingress removeIngressClass(Ingress v1) {
		return editIngressClass(v1, null);
	}

	private static Ingress editIngressClass(Ingress v1, String ingressClass) {
		return new IngressBuilder(v1)
			.editSpec()
			.withIngressClassName(ingressClass)
			.endSpec()
			.build();
	}

	private static Ingress addHttpFalse(Ingress v1) {
		return changeAnnotations(v1, Map.of(IngressAnnotation.ALLOW_HTTP.getName(), "false"));
	}

	private static Ingress addHttpTrue(Ingress v1) {
		return changeAnnotations(v1, Map.of(IngressAnnotation.ALLOW_HTTP.getName(), "true"));
	}

	private static Ingress removeAnnotations(Ingress v1) {
		return changeAnnotations(v1, Map.of());
	}

	private static Ingress changeAnnotations(Ingress v1, Map<String, String> annotations) {
		return new IngressBuilder(v1)
			.editMetadata()
			.withAnnotations(annotations)
			.endMetadata()
			.build();
	}

	private static boolean checkNo2NoHttp(Gateway before, Gateway after) {
		return checkTlsOnly(before) && checkTlsOnly(after);
	}

	private static boolean checkNo2YesHttp(Gateway before, Gateway after) {
		return checkTlsOnly(before) && checkHttp(after);
	}

	private static boolean checkYes2NoHttp(Gateway before, Gateway after) {
		return checkHttp(before) && checkTlsOnly(after);
	}

	private static boolean checkDefaul2DefaultSelector(Gateway before, Gateway after) {
		return checkDefaultSelector(before) && checkDefaultSelector(after);
	}

	private static boolean checkCustom2DefaultSelector(Gateway before, Gateway after) {
		return checkCustomSelector(before) && checkDefaultSelector(after);
	}

	private static boolean checkDefault2CustomSelector(Gateway before, Gateway after) {
		return checkDefaultSelector(before) && checkCustomSelector(after);
	}

	private static Ingress addDefaultSelector(Ingress v1) {
		return changeAnnotations(v1, Map.of(IngressAnnotation.ISTIO_SELECTOR.getName(), IngressAnnotation.ISTIO_SELECTOR.getDefaultValue()));
	}

	private static Ingress addCustomSelector(Ingress v1) {
		return changeAnnotations(v1, Map.of(IngressAnnotation.ISTIO_SELECTOR.getName(), CUSTOM_SELECTOR));
	}

	private static Ingress addNewTls(Ingress v1) {
		return new IngressBuilder(v1)
			.editSpec()
			.addToTls(createTls("tls.example.com", "tls-example-com-tls"))
			.endSpec()
			.build();
	}

	private static Ingress updateTlsHost(Ingress v1) {
		return new IngressBuilder(v1)
			.editSpec()
			.withTls(createTls("tls.example.com", "www-example-com-tls"))
			.endSpec()
			.build();
	}

	private static Ingress updateTlsSecret(Ingress v1) {
		return new IngressBuilder(v1)
			.editSpec()
			.withTls(createTls("www.example.com", "tls-example-com-tls"))
			.endSpec()
			.build();
	}

	private static Ingress updateTlsBoth(Ingress v1) {
		return new IngressBuilder(v1)
			.editSpec()
			.withTls(createTls("tls.example.com", "tls-example-com-tls"))
			.endSpec()
			.build();
	}

	private static Ingress removeTls(Ingress v1) {
		return new IngressBuilder(v1)
			.editSpec()
			.withTls(createTls("www.example.com", "www-example-com-tls"))
			.endSpec()
			.build();
	}

	@SuppressWarnings("unchecked")
	private static boolean checkTls(Gateway toCheck, Matcher<Server>... machers) {
		List<Server> servers = Optional.ofNullable(toCheck)
			.map(Gateway::getSpec)
			.map(GatewaySpec::getServers)
			.orElseGet(List::of);

		assertThat(servers, containsInAnyOrder(machers));

		return true;
	}

	private static boolean checkOne2TwoTls(Gateway before, Gateway after) {
		return checkOneTls(before) && checkTwoTls(after);
	}

	private static boolean checkTwo2OneTls(Gateway before, Gateway after) {
		return checkTwoTls(before) && checkOneTls(after);
	}

	@SuppressWarnings("unchecked")
	private static boolean checkOneTls(Gateway toCheck) {
		return checkTls(toCheck, TLS_SERVER_MATCHER.apply("www.example.com", "www-example-com-tls"));
	}

	@SuppressWarnings("unchecked")
	private static boolean checkTwoTls(Gateway toCheck) {
		return checkTls(toCheck, TLS_SERVER_MATCHER.apply("www.example.com", "www-example-com-tls"), TLS_SERVER_MATCHER.apply("tls.example.com", "tls-example-com-tls"));
	}

	@SuppressWarnings("unchecked")
	private static boolean checkNewHostTls(Gateway before, Gateway after) {
		return checkTls(before, TLS_SERVER_MATCHER.apply("www.example.com", "www-example-com-tls")) && checkTls(after, TLS_SERVER_MATCHER.apply("tls.example.com", "www-example-com-tls"));
	}

	@SuppressWarnings("unchecked")
	private static boolean checkNewSecretTls(Gateway before, Gateway after) {
		return checkTls(before, TLS_SERVER_MATCHER.apply("www.example.com", "www-example-com-tls")) && checkTls(after, TLS_SERVER_MATCHER.apply("www.example.com", "tls-example-com-tls"));
	}

	@SuppressWarnings("unchecked")
	private static boolean checkNewBothTls(Gateway before, Gateway after) {
		return checkTls(before, TLS_SERVER_MATCHER.apply("www.example.com", "www-example-com-tls")) && checkTls(after, TLS_SERVER_MATCHER.apply("tls.example.com", "tls-example-com-tls"));
	}

	private static boolean checkCreated(Gateway before, Gateway after) {
		assertNull(before);
		assertNotNull(after);
		return true;
	}

	private static boolean checkRemoved(Gateway before, Gateway after) {
		assertNotNull(before);
		assertNull(after);
		return true;
	}

	private static boolean checkNoStatus(Ingress after) {
		return getStatusIp(after).isEmpty();
	}

	private static boolean checkStatus(Ingress after) {
		Optional<String> statusIpAfter = getStatusIp(after);
		return statusIpAfter.isPresent() && STATUS_IP_01.equals(statusIpAfter.get());
	}

	private static Optional<String> getStatusIp(Ingress ingress) {
		List<LoadBalancerIngress> list = Optional.of(ingress)
			.map(Ingress::getStatus)
			.map(IngressStatus::getLoadBalancer)
			.map(LoadBalancerStatus::getIngress)
			.orElseGet(List::of);
		return list.stream()
			.findAny()
			.map(LoadBalancerIngress::getIp);
	}
}
