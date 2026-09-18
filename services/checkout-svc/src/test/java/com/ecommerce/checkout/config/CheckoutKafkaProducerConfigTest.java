package com.ecommerce.checkout.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the "checkout never blocks on Kafka" requirement: the
 * producer's {@code max.block.ms} must stay bounded so a down broker costs
 * ~1.5s instead of the Kafka default 60s metadata-fetch stall.
 *
 * <p>Light-weight by design: loads ONLY the main {@code application.yml}
 * through Spring's own YAML loader and configuration-property machinery — no
 * Spring context, no broker, no eureka/config-server noise. Guards the
 * binding {@code spring.kafka.producer.properties.max.block.ms} = {@code 1500}
 * all the way into the producer configuration map {@code KafkaProducer}
 * consumes.
 */
class CheckoutKafkaProducerConfigTest {

    /**
     * The bound value must live at the exact producer property path
     * (regression: the Kafka default is 60000ms, which would stall checkout
     * ~60s while the broker is down).
     */
    @Test
    void producerMaxBlockMsIsBoundedAtExactBindingPath() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("checkout-application", new ClassPathResource("application.yml"));
        assertThat(sources).isNotEmpty();

        Binder binder = new Binder(ConfigurationPropertySources.from(sources));
        String maxBlockMs = binder
                .bind("spring.kafka.producer.properties.max.block.ms", String.class)
                .orElse(null);

        assertThat(maxBlockMs).isEqualTo("1500");
    }

    /**
     * End-to-end: the same value lands in the map the producer factory passes
     * to {@code KafkaProducer} (KafkaProperties binds it as a String, which
     * ProducerConfig parses back to int when the producer is created).
     */
    @Test
    void producerMaxBlockMsReachesKafkaProducerConfig() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("checkout-application", new ClassPathResource("application.yml"));

        BindResult<KafkaProperties> bound = new Binder(ConfigurationPropertySources.from(sources))
                .bind("spring.kafka", Bindable.of(KafkaProperties.class));
        assertThat(bound.isBound()).isTrue();

        Map<String, Object> producerConfigs = bound.get().buildProducerProperties(null);
        assertThat(producerConfigs).containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, "1500");
    }
}