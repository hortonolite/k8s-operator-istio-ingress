package org.jresearch.k8s.operator.istio.ingress;

import java.time.Duration;

import javax.inject.Inject;

import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

@QuarkusMain
public class IstioIngressOperator implements QuarkusApplication {

	@Inject
	KubernetesClient kubernetesClient;
	@Inject
	IngressController ingressController;

	public static void main(String... args) {
		Quarkus.run(IstioIngressOperator.class, args);
	}

	@Override
	public int run(String... args) throws Exception {
		SharedInformerFactory informerFactory = kubernetesClient.informers();

		try (SharedIndexInformer<Ingress> informer = informerFactory.sharedIndexInformerFor(Ingress.class, Duration.ofMinutes(1).toMillis())) {

			informer.addEventHandler(ingressController);

			informerFactory.startAllRegisteredInformers();
			informerFactory.addSharedInformerEventListener(ex -> Log.errorf(ex, "Some error while listening ingress updates: %s", ex.getMessage()));

			Quarkus.waitForExit();
		} catch (KubernetesClientException ex) {
			Log.errorf(ex, "Kubernetes client exception : %s", ex.getMessage());
			return 1;
		}
		return 0;
	}
}
