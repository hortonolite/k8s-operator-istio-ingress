package org.jresearch.k8s.operator.istio.ingress;

public interface ChangeBiPredicate<T, U> {
	boolean test(T t0, U u0, T t1, U u1);

	public static <T, U> ChangeBiPredicate<T, U> wrap(ChangePredicate<? super U> istioPredicate) {
		return (t0, u0, t1, u1) -> istioPredicate.test(u0, u1);
	}

}
