package org.jresearch.k8s.operator.istio.ingress.model;

import static org.mapstruct.MappingConstants.ComponentModel.*;
import static org.mapstruct.ReportingPolicy.*;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPath;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRule;
import io.fabric8.kubernetes.api.model.networking.v1.IngressServiceBackend;

@Mapper(componentModel = CDI, unmappedSourcePolicy = WARN, unmappedTargetPolicy = ERROR)
public interface OperatorMapper {

	@Mapping(target = "paths", source = "http.paths")
	@BeanMapping(ignoreUnmappedSourceProperties = { "additionalProperties" })
	Rule map(IngressRule rule);

	@Mapping(target = "service", source = "backend.service")
	@BeanMapping(ignoreUnmappedSourceProperties = { "additionalProperties" })
	Path map(HTTPIngressPath rule);

	@BeanMapping(ignoreUnmappedSourceProperties = { "additionalProperties" })
	Service map(IngressServiceBackend backend);

	default PathType map(String pathType) {
		return PathType.byString(pathType).orElse(null);
	}

}
