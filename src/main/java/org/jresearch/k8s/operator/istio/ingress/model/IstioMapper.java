package org.jresearch.k8s.operator.istio.ingress.model;

import static org.mapstruct.MappingConstants.ComponentModel.*;
import static org.mapstruct.ReportingPolicy.*;

import java.util.List;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import io.fabric8.istio.api.networking.v1beta1.PortBuilder;
import io.fabric8.istio.api.networking.v1beta1.Server;
import io.fabric8.istio.api.networking.v1beta1.ServerTLSSettings;
import io.fabric8.istio.api.networking.v1beta1.ServerTLSSettingsBuilder;
import io.fabric8.istio.api.networking.v1beta1.ServerTLSSettingsTLSmode;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressTLS;

@Mapper(componentModel = CDI, unmappedSourcePolicy = WARN, unmappedTargetPolicy = ERROR)
public interface IstioMapper {

	@SuppressWarnings("boxing")
	Integer HTTPS_PORT = 443;
	String HTTPS_PROTOCOL = "HTTPS";
	@SuppressWarnings("boxing")
	Integer HTTP_PORT = 80;
	String HTTP_PROTOCOL = "HTTP";

	@Mapping(target = "tls", source = "secret")
	@Mapping(target = "port", constant = HTTPS_PROTOCOL)
	@Mapping(target = "bind", ignore = true)
	@Mapping(target = "defaultEndpoint", ignore = true)
	@Mapping(target = "name", ignore = true)
	@Mapping(target = "additionalProperties", ignore = true)
	Server mapHttps(Tls tls);

	@Mapping(target = "port", constant = HTTP_PROTOCOL)
	@Mapping(target = "hosts", source = "httpHosts")
	@Mapping(target = "tls", ignore = true)
	@Mapping(target = "bind", ignore = true)
	@Mapping(target = "defaultEndpoint", ignore = true)
	@Mapping(target = "name", ignore = true)
	@Mapping(target = "additionalProperties", ignore = true)
	@BeanMapping(ignoreUnmappedSourceProperties = { "istioSelector", "name", "namespace", "rules", "tls", "ownerInfo", "httpsOnly" })
	Server mapHttp(RoutingInfo info);

	default ServerTLSSettings mapSecretName(String secretName) {
		return new ServerTLSSettingsBuilder()
			.withCredentialName(secretName)
			.withMode(ServerTLSSettingsTLSmode.SIMPLE)
			.build();
	}

	default io.fabric8.istio.api.networking.v1beta1.Port mapPort(String protocol) {
		return new PortBuilder()
			.withName(protocol.toLowerCase())
			.withNumber(HTTP_PROTOCOL.equals(protocol) ? HTTP_PORT : HTTPS_PORT)
			.withProtocol(protocol)
			.build();
	}

	@Mapping(target = "secret", source = "secretName")
	@BeanMapping(ignoreUnmappedSourceProperties = { "additionalProperties" })
	Tls map(IngressTLS ingressTls);

	List<Server> mapHttps(List<Tls> tls);

	@Mapping(target = "name", source = "metadata.name")
	@Mapping(target = "uid", source = "metadata.uid")
	@BeanMapping(ignoreUnmappedSourceProperties = { "fullResourceName", "plural", "singular", "markedForDeletion", "additionalProperties", "spec", "status" })
	OwnerInfo map(Ingress ingress);

}
