package com.transit.service;

import com.transit.dto.AiApiBankProviderGroupView;
import com.transit.dto.PublicModel;
import com.transit.dto.PublicUpstream;
import com.transit.mapper.ChannelMapper;
import com.transit.mapper.ModelMappingMapper;
import com.transit.model.Channel;
import com.transit.model.ModelMapping;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class PublicModelPresentationIntegrationTests {
    @Autowired private ChannelMapper channels;
    @Autowired private ModelMappingMapper mappings;
    @Autowired private ModelIdentityService identities;
    @Autowired private PublicModelPresentationService presentation;
    @Autowired private PublicModelMarketplaceService marketplace;

    @Test
    void platformAndAiApiBankShareCanonicalMetadataButKeepTheirOwnRoutePlanAndPrice() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String canonicalName = "gpt-market-" + suffix;
        Channel platformChannel = channel("haoee", "平台智能路由");
        Channel bankChannel = channel("aiapibank", "AiAPIBank");
        ModelMapping platformMapping = mapping(platformChannel, canonicalName, canonicalName, "text");
        ModelMapping bankMapping = mapping(bankChannel, "aiapibank/team/" + canonicalName,
                "openai/" + canonicalName, "reasoning");

        String platformKey = identities.register(platformChannel, platformMapping, "openai", ModelIdentityService.RANK_MAPPING);
        String bankKey = identities.register(bankChannel, bankMapping, "openai", ModelIdentityService.RANK_PROVIDER_CATALOG);

        PublicModel platform = offer(canonicalName, canonicalName, "10");
        PublicModel bank = offer("aiapibank/team/" + canonicalName, "openai/" + canonicalName, "8");
        bank.setPricingMessage("AiAPIBank 折后采购价 × 1.1");
        bank.setPricingSourceUrl("https://vendor.invalid/internal-pricing");
        bank.setUpstreams(List.of(new PublicUpstream("aiapibank", "AiAPIBank", null, null)));
        bank.setProviderGroup(AiApiBankProviderGroupView.builder().slug("team").name("团队套餐").build());
        presentation.enrich(List.of(platform, bank));

        assertThat(platformKey).isEqualTo(bankKey).isEqualTo("openai:" + canonicalName);
        assertThat(platform).extracting(PublicModel::getPublisherCode, PublicModel::getCapability,
                PublicModel::getInputModalities, PublicModel::getOutputModalities, PublicModel::getProtocols)
                .containsExactly("openai", "reasoning", "text", "text", "chat-completions");
        assertThat(bank).extracting(PublicModel::getPublisherCode, PublicModel::getCapability,
                PublicModel::getInputModalities, PublicModel::getOutputModalities, PublicModel::getProtocols)
                .containsExactly("openai", "reasoning", "text", "text", "chat-completions");
        assertThat(platform.getPlanName()).isEqualTo("智能路由");
        assertThat(bank.getRouteName()).isEqualTo("AiAPIBank");
        assertThat(bank.getPlanName()).isEqualTo("团队套餐");
        assertThat(bank.getPricingMessage()).isEqualTo("本站公开销售价已核验");
        assertThat(bank.getPricingSourceUrl()).isNull();
        assertThat(platform.getMinInputPricePerMillion()).isEqualByComparingTo("10");
        assertThat(bank.getMinInputPricePerMillion()).isEqualByComparingTo("8");
        assertThat(marketplace.comparison(List.of(platform, bank), canonicalName).offers()).hasSize(2);
    }

    private Channel channel(String source, String name) {
        Channel channel = Channel.builder().name(name).type("openai").sourceCode(source).sourceName(name)
                .protocolType("openai-chat").baseUrl("https://example.invalid/v1").apiKey("encrypted")
                .models("placeholder").enabled(true).healthStatus("HEALTHY").build();
        channels.insert(channel);
        return channel;
    }

    private ModelMapping mapping(Channel channel, String publicName, String upstreamName, String capability) {
        ModelMapping mapping = ModelMapping.builder().publicModelName(publicName).channelModelName(upstreamName)
                .channelId(channel.getId()).enabled(true).billingEnabled(true).billingMode("PAID")
                .pricingStatus("VERIFIED").vendor("openai").capability(capability)
                .inputModalities("text").outputModalities("text").protocols("chat-completions")
                .pricingUnit("TOKEN").inputPricePerMillion(BigDecimal.ONE)
                .outputPricePerMillion(BigDecimal.ONE).build();
        mappings.insert(mapping);
        return mapping;
    }

    private PublicModel offer(String publicName, String upstreamName, String inputPrice) {
        PublicModel model = new PublicModel();
        model.setPublicName(publicName); model.setUpstreamModelName(upstreamName);
        model.setAvailable(true); model.setBillingConfigured(true); model.setBillingMode("PAID");
        model.setPricingUnit("TOKEN"); model.setMinInputPricePerMillion(new BigDecimal(inputPrice));
        model.setMinOutputPricePerMillion(new BigDecimal(inputPrice));
        return model;
    }
}
