package org.jresearch.k8s.operator.istio.ingress;

public interface ChangePredicate<U> {
	boolean test(U u0, U u1);

}
