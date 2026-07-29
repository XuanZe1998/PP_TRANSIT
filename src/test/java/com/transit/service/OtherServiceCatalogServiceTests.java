package com.transit.service;

import com.transit.mapper.OtherServiceMapper;
import com.transit.mapper.PlusProductMapper;
import com.transit.model.OtherService;
import com.transit.model.PlusProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OtherServiceCatalogServiceTests {

    @Mock private OtherServiceMapper otherServiceMapper;
    @Mock private PlusProductMapper plusProductMapper;

    private OtherServiceCatalogService service;
    private AtomicReference<PlusProduct> insertedProduct;

    @BeforeEach
    void setUp() {
        service = new OtherServiceCatalogService(otherServiceMapper, plusProductMapper);
        insertedProduct = new AtomicReference<>();
        doAnswer(invocation -> {
            PlusProduct product = invocation.getArgument(0);
            product.setId(7L);
            insertedProduct.set(product);
            return 1;
        }).when(plusProductMapper).insert(any(PlusProduct.class));
        when(plusProductMapper.selectById(7L)).thenAnswer(invocation -> insertedProduct.get());
    }

    @Test
    void everyServiceOwnsOrderAndPriceSettings() {
        OtherService request = OtherService.builder()
                .name("Chat GPT Plus")
                .description("One month service")
                .priceCents(2_000L)
                .serviceFeeCents(500L)
                .currency("usd")
                .purchaseEnabled(true)
                .enabled(true)
                .sortOrder(10)
                .build();

        OtherService created = service.create(request);

        assertThat(created.getName()).isEqualTo("Chat GPT Plus");
        assertThat(created.getPriceCents()).isEqualTo(2_000L);
        assertThat(created.getServiceFeeCents()).isEqualTo(500L);
        assertThat(created.getAmountCents()).isEqualTo(2_500L);
        assertThat(created.getCurrency()).isEqualTo("USD");
        assertThat(created.getPurchaseEnabled()).isTrue();
        assertThat(created.getOrderEnabled()).isTrue();
        assertThat(insertedProduct.get().getName()).isEqualTo("Chat GPT Plus");
        assertThat(insertedProduct.get().getEnabled()).isTrue();
    }

    @Test
    void serviceCanBeDisplayedWithoutAllowingOrders() {
        OtherService created = service.create(OtherService.builder()
                .name("Consulting")
                .priceCents(10_000L)
                .currency("CNY")
                .purchaseEnabled(false)
                .enabled(true)
                .build());

        assertThat(created.getPurchaseEnabled()).isFalse();
        assertThat(created.getOrderEnabled()).isFalse();
        assertThat(insertedProduct.get().getEnabled()).isFalse();
    }

    @Test
    void invalidCurrencyIsRejected() {
        assertThatThrownBy(() -> service.create(OtherService.builder()
                .name("Broken service")
                .currency("US")
                .build()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void managedLocalImageUrlIsAccepted() {
        String imageUrl = "/api/public/other-service-images/123e4567-e89b-12d3-a456-426614174000.webp";

        OtherService created = service.create(OtherService.builder()
                .name("Image service")
                .imageUrl(imageUrl)
                .build());

        assertThat(created.getImageUrl()).isEqualTo(imageUrl);
        assertThat(insertedProduct.get().getImageUrl()).isEqualTo(imageUrl);
    }
}
