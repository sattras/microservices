package com.acme.stream.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.ObservationTextPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.retry.annotation.Backoff;

import com.acme.kafka.outbox.avro.EventKey;
import com.acme.kafka.outbox.avro.EventValue;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.observability.micrometer.Micrometer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@SpringBootApplication
@Slf4j
public class Application {
    static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${remote-url.payment-service}")
    static String paymentUrl;

    @Value("${remote-url.stock-service}")
    static String stockUrl;

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Configuration
    class ObservationConfig {
        @Bean
        public ObservationTextPublisher printingObservationHandler() {
            return new ObservationTextPublisher();
        }
    }

    @Component
    @RequiredArgsConstructor
    static class StreamHandler {
        private final Supplier<Long> latency = () -> new Random().nextLong(500);

        private final ObservationRegistry registry;

        @RetryableTopic(attempts = "5", backoff = @Backoff(delay = 2_000, maxDelay = 10_000, multiplier = 2))
        @KafkaListener(id = "order-outbox", topics = "order.outbox")
        public void listen(ConsumerRecord<EventKey, EventValue> record) throws Exception {
            log.info("receiving outbox msg => topics: {}, key: {}, value: {}", record.topic(), record.key(), record.value());
            var eventId = String.valueOf(record.key().getEventId());

            // TODO: Initial and start saga (synchonized flow via rest-api)
            // do payment -> reserve stock -> generate delivery order
            // if failed, revert payment (if exist) -> revert reserved stock (if exist)
            var eventType = String.valueOf(record.value().getEventType());
            switch (eventType) {
                case "order_created":
                    var order = MAPPER.readValue(record.value().getPayload().toString(), Order.class);
                    onOrderCreated(eventId, order);
                    break;
                default:
                    log.info("default event_type => {}", eventType);
            }

            // TODO: Enhance saga (asynchonized flow via messaging)
        }

        @DltHandler
        public void listenDlt(ConsumerRecord<EventKey, EventValue> record) {
            log.info("receiving dlt msg => topics: {}, key: {}, value: {}", record.topic(), record.key(), record.value());
        }

        public void onOrderCreated(String eventId, Order order) {
            var lat = latency.get();
            var saga = new CreateOrderSagaWorkflow();
            Mono.just(order)
                    .name("stream.order.created")
                    .tag("latency", lat > 250 ? "high" : "low")
                    .tap(Micrometer.observation(registry))
                    .delayUntil(o -> saga.execute(eventId, o))
                    .block(Duration.ofSeconds(5));
        }
    }

    static class CreateOrderSagaWorkflow implements SagaWorkflow<Order> {
        static final List<SagaStep> steps = Arrays.asList(
                new SagaStep<Payment, Order>() {
                    static final WebClient webClient = WebClient.builder()
                            .baseUrl(paymentUrl)
                            .build();

                    @Override
                    public Payment bind(Order o) {
                        return new Payment(null, null, null, o.customerCode, o.orderNo, o.amount);
                    }

                    @Override
                    public Mono<Payment> execute(String eventId, Payment o) {
                        log.info("creating a payment => url: {}/payments, payload: {}", paymentUrl, o);
                        return webClient.post()
                                .uri("/payments")
                                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                                .header("x-request-id", eventId)
                                .body(Mono.just(o), Payment.class)
                                .retrieve()
                                .bodyToMono(Payment.class);
                    }

                    @Override
                    public Mono<Payment> rollback(String eventId, Payment o) {
                        log.info("rolling back payment => url: {}/payments/{}", paymentUrl, o.id);
                        return webClient.delete()
                                .uri(String.format("/payments/%s", o.id))
                                .header("x-request-id", eventId)
                                .retrieve()
                                .bodyToMono(Payment.class);
                    }
                },
                new SagaStep<Stock, Order>() {
                    static final WebClient webClient = WebClient.builder()
                            .baseUrl(stockUrl)
                            .build();

                    @Override
                    public Stock bind(Order o) {
                        return new Stock(null, o.orderNo, o.orderDate, o.customerCode, o.items.stream()
                                        .map(i -> new StockItem(i.sku, i.barcode, i.qty))
                                        .collect(Collectors.toList()));
                    }

                    @Override
                    public Mono<Stock> execute(String eventId, Stock o) {
                        log.info("allocating stock => url: {}/stocks, payload: {}", stockUrl, o);
                        return webClient.post()
                                .uri("/stocks")
                                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                                .header("x-request-id", eventId)
                                .body(Mono.just(o), Stock.class)
                                .retrieve()
                                .bodyToMono(Stock.class);
                    }

                    @Override
                    public Mono<Stock> rollback(String eventId, Stock o) {
                        log.info("rolling back stock => url: {}/stocks/{}", stockUrl, o.id);
                        return webClient.delete()
                                .uri(String.format("/stocks/%s", o.id))
                                .header("x-request-id", eventId)
                                .retrieve()
                                .bodyToMono(Stock.class);
                    }
                }
        );

        @Override
        public Mono<Void> execute(String eventId, Order order) {
            return Flux.fromIterable(steps)
                    .flatMap(s -> s.execute(eventId, s.bind(order)))
                    .collectList()
                    .onErrorResume(e -> Flux.fromIterable(steps)
                            .flatMap(s -> s.rollback(eventId, s.bind(order)))
                            .then()
                    );
        }
    }

    public interface SagaWorkflow<T> {
        Mono<Void> execute(String eventId, T t);
    }

    public interface SagaStep<T, V> {
        T bind(V v);
        Mono<T> execute(String eventId, T t);
        Mono<T> rollback(String eventId, T t);
    }

    record Order(String id, String orderNo, Date orderDate, String customerCode, List<OrderItem> items, Double amount) {}
    record OrderItem(String sku, String barcode, Integer qty, Double amount) {}
    record Payment(String id, String paymentNo, Date paymentDate, String customerCode, String refNo, Double amount) {}
    record Stock(String id, String orderNo, Date orderDate, String customerCode, List<StockItem> items) {}
    record StockItem(String sku, String barcode, Integer qty) {}
}
