package org.jresearch.k8s.operator.istio.ingress;

import static org.awaitility.Awaitility.*;
import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
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
import me.snowdrop.istio.api.networking.v1beta1.Gateway;
import me.snowdrop.istio.api.networking.v1beta1.GatewayList;
import me.snowdrop.istio.api.networking.v1beta1.GatewaySpec;
import me.snowdrop.istio.api.networking.v1beta1.Port;
import me.snowdrop.istio.api.networking.v1beta1.Server;
import me.snowdrop.istio.api.networking.v1beta1.ServerTLSSettingsMode;
import me.snowdrop.istio.api.networking.v1beta1.VirtualService;
import me.snowdrop.istio.api.networking.v1beta1.VirtualServiceList;

@QuarkusTest
@WithKubernetesTestServer(port = 7890)
class IngressControllerTest {

	@KubernetesTestServer
	KubernetesServer mockServer;
	@Inject
	IngressController controller;

	private static final String TEST_NAMESPACE = "test";
	private static final String NAME_ISTIO = "test-ingress-istio";
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
	private static final Predicate<Gateway> TLS = IngressControllerTest::checkTls;
	private static final Predicate<Gateway> HTTP = IngressControllerTest::checkHttp;

	private static <I, W> BiPredicate<I, W> wrap(Predicate<? super W> resourcePredicate) {
		return (i, o) -> resourcePredicate.test(o);
	}

	@AfterEach
	void cleanUp() {
		try (NamespacedKubernetesClient client = mockServer.getClient()) {
			try (NetworkAPIGroupDSL network = client.network()) {
				try (V1NetworkAPIGroupDSL v1 = network.v1()) {
					assertEquals(Boolean.TRUE, v1.ingresses().inNamespace(TEST_NAMESPACE).withName(NAME_ISTIO).delete());
				}
			}
		}
	}

	@Getter
	@AllArgsConstructor
	private static enum Params {
		SET01(getIstioIngress(), EXIST, EXIST),
		SET02(getIstioIngress(), OWNER, OWNER),
		SET03(getIstioIngress(), TLS, TODO),
		SET04(getIngressWithRulesNoHttpDefault(), TLS, TODO),
		SET05(getIngressWithRulesHttpFalse(), TLS, TODO),
		SET06(getIngressWithRulesHttpTrue(), HTTP, TODO),
		SET07(getIngressWithIstioSelectorDefault(), gw -> checkSelector(gw, EXPECTED_DEFAULT_SELECTOR), TODO),
		SET08(getIngressWithIstioSelectorSpecified(), gw -> checkSelector(gw, EXPECTED_CUSTOM_SELECTOR), TODO),
		;

		private Params(Ingress testIngress, Predicate<? super Gateway> testGateway, Predicate<? super VirtualService> testVirtualService) {
			this(testIngress, wrap(testGateway), wrap(testVirtualService));
		}

		private Params(Ingress testIngress, BiPredicate<? super Ingress, ? super Gateway> testGateway, Predicate<? super VirtualService> testVirtualService) {
			this(testIngress, testGateway, wrap(testVirtualService));
		}

		private Params(Ingress testIngress, Predicate<? super Gateway> testGateway, BiPredicate<? super Ingress, ? super VirtualService> testVirtualService) {
			this(testIngress, wrap(testGateway), testVirtualService);
		}

		private final Ingress testIngress;
		private final BiPredicate<? super Ingress, ? super Gateway> testGateway;
		private final BiPredicate<? super Ingress, ? super VirtualService> testVirtualService;
	}

	@ParameterizedTest
	@EnumSource
	@DisplayName("Should create Istio GW and VS for provided ingress")
	void testCreate(Params testData) {

		var testIngressIstio = testData.getTestIngress();
		var testGateway = testData.getTestGateway();
		var testVirtualService = testData.getTestVirtualService();

		try (NamespacedKubernetesClient client = mockServer.getClient()) {
			try (NetworkAPIGroupDSL network = client.network()) {
				try (V1NetworkAPIGroupDSL v1 = network.v1()) {
					// Create and call controller
					v1.ingresses().create(testIngressIstio);
					controller.onAdd(testIngressIstio);
					assertNotNull(v1.ingresses().inNamespace(TEST_NAMESPACE).withName(NAME_ISTIO).get());

					// Check add
					await().until(() -> getGateway(client, NAME_ISTIO, TEST_NAMESPACE), gw -> testGateway.test(testIngressIstio, gw));
					await().until(() -> getVirtualService(client, NAME_ISTIO, TEST_NAMESPACE), vs -> testVirtualService.test(testIngressIstio, vs));
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

			// IngressController controller = new IngressController(client);

			try (NetworkAPIGroupDSL network = client.network()) {
				try (V1NetworkAPIGroupDSL v1 = network.v1()) {
					// Create and call controller
					v1.ingresses().create(testIngressGeneral);
					controller.onAdd(testIngressGeneral);
					assertNotNull(v1.ingresses().inNamespace(TEST_NAMESPACE).withName(NAME_ISTIO).get());

					// Chek ignore general
					Resource<Gateway> testGatewayGeneral = client.resources(Gateway.class, GatewayList.class).inNamespace(TEST_NAMESPACE).withName(NAME_ISTIO);
					await().during(Duration.ofSeconds(1)).failFast(() -> EXIST.test(testGatewayGeneral)).until(() -> Boolean.TRUE);
					Resource<VirtualService> testVirtualServiceGeneral = client.resources(VirtualService.class, VirtualServiceList.class).inNamespace(TEST_NAMESPACE).withName(NAME_ISTIO);
					await().during(Duration.ofSeconds(1)).failFast(() -> EXIST.test(testVirtualServiceGeneral)).until(() -> Boolean.TRUE);
				}
			}
		}

	}

	@Test
	@DisplayName("REMOVE IT!!!!")
	void testControllerT____to____remove____TODO() {
//		String testNamespace = "test";
//		String nameIstio = "test-ingress-istio";
//		String nameGeneral = "test-ingress-general";

		Ingress testIngressIstio = getIstioIngress();

		try (NamespacedKubernetesClient client = mockServer.getClient()) {

			// IngressController controller = new IngressController(client);

			try (NetworkAPIGroupDSL network = client.network()) {
				try (V1NetworkAPIGroupDSL v1 = network.v1()) {
					// update and call controller
					// v1.ingresses().create(testIngress);
					Ingress testNewIngress = new IngressBuilder(testIngressIstio)
						.editMetadata()
						.withResourceVersion("2")
						.and()
						.build();
					controller.onUpdate(testIngressIstio, testNewIngress);
//					assertNotNull(v1.ingresses().inNamespace(testNamespace).withName(nameIstio).get());

					// Check update
					// Assertions.assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
					// testGateway(client, testNamespace));
					// Assertions.assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
					// testVirtualService(client, testNamespace));

					// Remove and call controller
					v1.ingresses().delete(testIngressIstio);
					controller.onDelete(testIngressIstio, false);
//					assertNull(v1.ingresses().inNamespace(testNamespace).withName(nameIstio).get());

					// Check remove
					// Assertions.assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
					// testGateway(client, testNamespace));
					// Assertions.assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
					// testVirtualService(client, testNamespace));

				}
			}
		}

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

	private static Ingress getIngressWithRulesNoHttpDefault() {
		return getIngress(Map.of(), () -> createSpec(IngressController.INGRESS_CLASSNAME, IngressControllerTest::createTls, IngressControllerTest::createRules));
	}

	private static Ingress getIngressWithRulesHttpFalse() {
		return getIngress(Map.of(IngressAnnotation.ALLOW_HTTP.getName(), "false"), () -> createSpec(IngressController.INGRESS_CLASSNAME, IngressControllerTest::createTls, IngressControllerTest::createRules));
	}

	private static Ingress getIngressWithRulesHttpTrue() {
		return getIngress(Map.of(IngressAnnotation.ALLOW_HTTP.getName(), "true"), () -> createSpec(IngressController.INGRESS_CLASSNAME, IngressControllerTest::createTls, IngressControllerTest::createRules));
	}

	private static Ingress getIngressWithIstioSelectorDefault() {
		return getIngress(Map.of(), () -> createSpec(IngressController.INGRESS_CLASSNAME, IngressControllerTest::createTls, IngressControllerTest::createRules));
	}

	private static Ingress getIngressWithIstioSelectorSpecified() {
		return getIngress(Map.of(IngressAnnotation.ISTIO_SELECTOR.getName(), "app=istio-ingressgateway,istio=ingressgateway"), () -> createSpec(IngressController.INGRESS_CLASSNAME, IngressControllerTest::createTls, IngressControllerTest::createRules));
	}

	private static Ingress getNonIstioIngress() {
		return getIngress(Map.of(), () -> createSpec(null, () -> List.of(), () -> List.of()));
	}

	private static Ingress getIstioIngress() {
		return getIngress(Map.of(), () -> createSpec(IngressController.INGRESS_CLASSNAME, IngressControllerTest::createTls, () -> List.of()));
	}

	private static Ingress getIngress(Map<String, String> annotations, Supplier<IngressSpec> spec) {
		return new IngressBuilder()
			.withMetadata(createMetadata(annotations))
			.withSpec(spec.get())
			.build();
	}

	private static ObjectMeta createMetadata(Map<String, String> annotations) {
		return new ObjectMetaBuilder()
			.withName(NAME_ISTIO)
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

	private static List<IngressRule> createRules() {
		return List.of(new IngressRuleBuilder()
			.withHost("http.example.com")
			.withHttp(createRuleValue())
			.build());
	}

	private static HTTPIngressRuleValue createRuleValue() {
		return new HTTPIngressRuleValueBuilder()
			.withPaths(createIngressPath())
			.build();
	}

	private static HTTPIngressPath createIngressPath() {
		return new HTTPIngressPathBuilder()
			.withPath("/path")
			.withPathType("ImplementationSpecific")
			.withBackend(createIngressBackend())
			.build();
	}

	private static IngressBackend createIngressBackend() {
		return new IngressBackendBuilder()
			.withService(createIngressServiceBackend())
			.build();
	}

	private static IngressServiceBackend createIngressServiceBackend() {
		return new IngressServiceBackendBuilder()
			.withName("backend")
			.withPort(createServiceBackendPort())
			.build();
	}

	private static ServiceBackendPort createServiceBackendPort() {
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
}
