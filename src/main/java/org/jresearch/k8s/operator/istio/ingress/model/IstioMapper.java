package org.jresearch.k8s.operator.istio.ingress.model;

import static org.mapstruct.MappingConstants.ComponentModel.*;
import static org.mapstruct.ReportingPolicy.*;

import java.util.List;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import io.fabric8.kubernetes.api.model.networking.v1.IngressTLS;
import me.snowdrop.istio.api.networking.v1beta1.Port;
import me.snowdrop.istio.api.networking.v1beta1.PortBuilder;
import me.snowdrop.istio.api.networking.v1beta1.Server;
import me.snowdrop.istio.api.networking.v1beta1.ServerTLSSettings;
import me.snowdrop.istio.api.networking.v1beta1.ServerTLSSettingsBuilder;
import me.snowdrop.istio.api.networking.v1beta1.ServerTLSSettingsMode;

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
	Server mapHttps(Tls tls);

	@Mapping(target = "port", constant = HTTP_PROTOCOL)
	@Mapping(target = "tls", ignore = true)
	@Mapping(target = "bind", ignore = true)
	@Mapping(target = "defaultEndpoint", ignore = true)
	@Mapping(target = "name", ignore = true)
	Server mapHttp(Rule rule);

	default ServerTLSSettings mapSecretName(String secretName) {
		return new ServerTLSSettingsBuilder()
			.withCredentialName(secretName)
			.withMode(ServerTLSSettingsMode.SIMPLE)
			.build();
	}

	default Port mapPort(String protocol) {
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

}
