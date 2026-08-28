package com.transit.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;

@Component
public class ChannelUrlPolicy {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("https");
    private final boolean allowPrivateAddresses;

    public ChannelUrlPolicy(@Value("${gateway.allow-private-upstreams:false}") boolean allowPrivateAddresses) {
        this.allowPrivateAddresses = allowPrivateAddresses;
    }

    public void validate(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            boolean localDevelopmentHttp = allowPrivateAddresses && "http".equals(scheme);
            if ((!ALLOWED_SCHEMES.contains(scheme) && !localDevelopmentHttp) || uri.getHost() == null || uri.getUserInfo() != null) {
                throw rejected();
            }
            if (allowPrivateAddresses) {
                return;
            }
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    throw rejected();
                }
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw rejected();
        }
    }

    private ResponseStatusException rejected() {
        return new ResponseStatusException(BAD_GATEWAY, "Upstream channel URL is not allowed");
    }
}
