package org.jresearch.k8s.operator.istio.ingress;

import java.time.Duration;
import java.util.concurrent.ExecutionException;

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

	public static void main(String... args) {
		Quarkus.run(IstioIngressOperator.class, args);
	}

	@Override
	public int run(String... args) throws Exception {
		SharedInformerFactory informerFactory = kubernetesClient.informers();

		try (SharedIndexInformer<Ingress> informer = informerFactory.sharedIndexInformerFor(Ingress.class, Duration.ofMinutes(1).toMillis())) {

			new IngressController(kubernetesClient, informer);

			informerFactory.startAllRegisteredInformers().get();
			informerFactory.addSharedInformerEventListener(ex -> Log.errorf(ex, "Some error while listening ingress updates: %s", ex.getMessage()));

			Quarkus.waitForExit();
		} catch (KubernetesClientException | ExecutionException ex) {
			Log.errorf(ex, "Kubernetes client exception : %s", ex.getMessage());
			return 1;
		} catch (InterruptedException ex) {
			Log.infof("Execution was interrupted: %s", ex.getMessage());
			Thread.currentThread().interrupt();
		}
		return 0;
	}
}
