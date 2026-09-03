package com.transit.service;

/**
 * Published after an AiAPIBank channel receives a new base credential.
 * The catalog listener runs after the channel transaction commits so the
 * newly encrypted key is visible to the model and pricing synchronizer.
 */
public record AiApiBankCredentialConfiguredEvent(Long channelId) {
}
