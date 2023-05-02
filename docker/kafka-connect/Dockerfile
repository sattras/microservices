FROM confluentinc/cp-kafka-connect:7.3.1

COPY outbox-router/target/*.jar /etc/kafka-connect/jars/

RUN confluent-hub install --no-prompt debezium/debezium-connector-mongodb:1.9.7