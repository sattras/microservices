package com.acme.service.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.ObservationTextPublisher;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.mongo.MongoProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.ReactiveMongoDatabaseFactory;
import org.springframework.data.mongodb.ReactiveMongoTransactionManager;
import org.springframework.data.mongodb.config.AbstractReactiveMongoConfiguration;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import reactor.core.observability.micrometer.Micrometer;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

@SpringBootApplication
public class Application {

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

    @Configuration
    @EnableReactiveMongoRepositories(considerNestedRepositories = true)
    @AllArgsConstructor
    class MongoConfig extends AbstractReactiveMongoConfiguration {
        private final MongoProperties mongoProperties;

        @Override
        public MongoClient reactiveMongoClient() {
            return MongoClients.create(mongoProperties.getUri());
        }

        @Override
        protected String getDatabaseName() {
            return mongoProperties.getDatabase();
        }

        @Bean
        public ReactiveMongoTransactionManager transactionManager(ReactiveMongoDatabaseFactory reactiveMongoDatabaseFactory) {
            return new ReactiveMongoTransactionManager(reactiveMongoDatabaseFactory);
        }
    }

    @RestController
    @RequiredArgsConstructor
    @Slf4j
    static class OrderController {
        private final OrderService service;

        @GetMapping("/orders/{id}")
        public Mono<Order> getOrder(@PathVariable("id") String id) {
            return service.getOrder(id);
        }

        @PostMapping("/orders")
        @ResponseStatus(HttpStatus.CREATED)
        public Mono<Order> createOrder(@RequestBody Order order,
                                       @RequestHeader(value = "x-request-id", required = false) String requestId) {
            return service.createOrder(order, requestId);
        }
    }

    @Service
    @RequiredArgsConstructor
    @Slf4j
    static class OrderService {
        private final ObjectMapper MAPPER = new ObjectMapper();
        private final Supplier<Long> latency = () -> new Random().nextLong(500);

        private final ObservationRegistry registry;
        private final OrderRepository orderRepository;
        private final OutboxRepository outboxRepository;

        public Mono<Order> getOrder(String id) {
            var lat = latency.get();
            return orderRepository.findById(id)
                    .doOnSuccess(o -> log.info("get order#{} => {}", id, o))
                    .delayElement(Duration.ofMillis(lat))
                    .name("service.order.get")
                    .tag("latency", lat > 250 ? "high" : "low")
                    .tap(Micrometer.observation(registry));
        }

        @Transactional
        public Mono<Order> createOrder(Order order, String eventId) {
            var lat = latency.get();
            return orderRepository.save(order)
                    .zipWhen(o -> {
                        try {
                            var payload = MAPPER.writeValueAsString(o);
                            return outboxRepository.save(new Outbox(null, eventId, "order_created", "order", payload));
                        } catch (JsonProcessingException e) {
                            return Mono.error(e);
                        }
                    })
                    .map(o -> o.getT1())
                    .doOnSuccess(o -> log.info("create new order => {}", o))
                    .delayElement(Duration.ofMillis(lat))
                    .name("service.order.create")
                    .tag("latency", lat > 250 ? "high" : "low")
                    .tap(Micrometer.observation(registry));
        }
    }

    @Document(collection = "orders")
    record Order(@Id String id, String orderNo, Date orderDate, String customerCode, List<OrderItem> items, Double amount) { }
    record OrderItem(String sku, String barcode, Integer qty, Double amount) {}
    @Document(collection = "outboxes")
    record Outbox(@Id String id, String eventId, String eventType, String aggregateType, String payload) {}
    @Repository
    interface OrderRepository extends ReactiveMongoRepository<Order, String> {}
    @Repository
    interface OutboxRepository extends ReactiveMongoRepository<Outbox, String> {}
}
