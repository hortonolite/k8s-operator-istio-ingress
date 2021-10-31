package org.jresearch.k8s.operator.istio.ingress;

import static io.fabric8.kubernetes.client.utils.KubernetesResourceUtil.getQualifiedName;

import java.util.List;

import org.jresearch.k8s.operator.istio.ingress.model.RoutingInfo;

import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

public class IngressController implements ResourceEventHandler<Ingress> {

	private KubernetesClient kubernetesClient;
	private SharedIndexInformer<Ingress> informer;

	public IngressController(KubernetesClient kubernetesClient, SharedIndexInformer<Ingress> informer) {
		this.kubernetesClient = kubernetesClient;
		this.informer = informer;
		informer.addEventHandler(this);
	}

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
		if (oldObj.getMetadata().getResourceVersion().equals(newObj.getMetadata().getResourceVersion())) {
			return;
		}
		Log.tracef("update %s", getQualifiedName(newObj));
		Log.tracef("before update %s", getQualifiedName(newObj));
		Uni.createFrom()
				.item(newObj)
				.emitOn(Infrastructure.getDefaultExecutor())
				.subscribe()
				.with(this::onAddOrUpdate);
		Log.tracef("after update %s", getQualifiedName(newObj));
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
		istioPratameters.forEach(info -> Log.infof("Remove certificate for: %s", info));
		// TODO
	}

	private void onDelete(Ingress ingress) {
		Log.tracef("on delete %s", getQualifiedName(ingress));
		// List<CertificateInfo> certPratameters = getIstioRoutingInfo(ingress);
		// certPratameters.forEach(info -> Log.infof("Remove certificate for: %s",
		// info));
		// TODO
	}

	private List<RoutingInfo> getIstioRoutingInfo(Ingress ingress) {
		// TODO Auto-generated method stub
		return null;
	}

}
