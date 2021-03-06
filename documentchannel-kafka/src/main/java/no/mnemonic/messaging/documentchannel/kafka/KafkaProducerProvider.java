package no.mnemonic.messaging.documentchannel.kafka;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.*;
import java.util.stream.Collectors;

import static org.apache.kafka.clients.producer.ProducerConfig.*;

/**
 * A provider which provides a kafka producer for a configured kafka cluster
 */
public class KafkaProducerProvider {

  private static final int DEFAULT_RETRIES = 5;
  private static final int DEFAULT_BATCH_SIZE = 2000;
  private static final int DEFAULT_LINGER_MILLIS = 1000;
  private static final int DEFAULT_MAX_REQUEST_SIZE = 1048576; // 1MB
  private static final int DEFAULT_TIMEOUT_MILLIS = 30000;
  private static final int DEFAULT_MAX_BLOCK_MILLIS = 10_000;
  private static final int DEFAULT_SEND_BUFFER_SIZE = 131072;

  public enum Acknowledgement {
    none("0"), leader("1"), all("all");

    private String value;

    Acknowledgement(String value) {
      this.value = value;
    }
  }

  public enum Compression {
    none("none"), gzip("gzip"), snappy("snappy"), lz4("lz4"), zstd("zstd");

    private String value;

    Compression(String value) {
      this.value = value;
    }
  }

  private final String kafkaHosts;
  private final int kafkaPort;
  private final int maxRequestSize;
  private final int requestTimeoutMs;
  private final int maxBlockMs;
  private final int sendBuffer;
  private final int batchSize;
  private final Compression compression;
  private final Acknowledgement acknowledgements;
  private final int lingerMs;
  private final int retries;

  private KafkaProducerProvider(
          String kafkaHosts,
          int kafkaPort,
          int maxRequestSize,
          int requestTimeoutMs,
          int maxBlockMs,
          int sendBuffer,
          int batchSize,
          Compression compression,
          Acknowledgement acknowledgements,
          int lingerMs,
          int retries
  ) {
    this.kafkaHosts = kafkaHosts;
    this.kafkaPort = kafkaPort;
    this.maxRequestSize = maxRequestSize;
    this.requestTimeoutMs = requestTimeoutMs;
    this.maxBlockMs = maxBlockMs;
    this.sendBuffer = sendBuffer;
    this.batchSize = batchSize;
    this.compression = compression;
    this.acknowledgements = acknowledgements;
    this.lingerMs = lingerMs;
    this.retries = retries;
  }

  public <T> KafkaProducer<String, T> createProducer(Class<T> type) {
    return new KafkaProducer<>(
            createProperties(),
            new StringSerializer(),  // Key serializer
            createSerializer(type) // Value serializer
    );
  }

  private <T> Serializer<T> createSerializer(Class<T> type) {
    if (type.equals(String.class)) {
      return (Serializer<T>) new StringSerializer();
    } else if (type.equals(byte[].class)) {
      return (Serializer<T>) new ByteArraySerializer();
    } else {
      throw new IllegalArgumentException("Invalid type: " + type);
    }
  }

  private Map<String, Object> createProperties() {
    Map<String, Object> properties = new HashMap<>();
    properties.put(BOOTSTRAP_SERVERS_CONFIG, createBootstrapServerList()); // expect List<String>
    properties.put(ACKS_CONFIG, acknowledgements.value);
    properties.put(COMPRESSION_TYPE_CONFIG, compression.value);
    properties.put(RETRIES_CONFIG, retries);
    properties.put(BATCH_SIZE_CONFIG, batchSize);
    properties.put(LINGER_MS_CONFIG, lingerMs);
    properties.put(CLIENT_ID_CONFIG, UUID.randomUUID().toString());
    properties.put(MAX_REQUEST_SIZE_CONFIG, maxRequestSize);
    properties.put(REQUEST_TIMEOUT_MS_CONFIG, requestTimeoutMs);
    properties.put(MAX_BLOCK_MS_CONFIG, maxBlockMs);
    properties.put(SEND_BUFFER_CONFIG, sendBuffer);
    return properties;
  }

  private List<String> createBootstrapServerList() {
    return Arrays.stream(kafkaHosts.split(","))
            .map(h -> String.format("%s:%d", h, kafkaPort))
            .collect(Collectors.toList());
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private String kafkaHosts;
    private int kafkaPort;

    private int maxRequestSize = DEFAULT_MAX_REQUEST_SIZE;
    private int requestTimeoutMs = DEFAULT_TIMEOUT_MILLIS;
    private int maxBlockMs = DEFAULT_MAX_BLOCK_MILLIS;
    private int sendBuffer = DEFAULT_SEND_BUFFER_SIZE;
    private int batchSize = DEFAULT_BATCH_SIZE;
    private Compression compression = Compression.none;
    private Acknowledgement acknowledgements = Acknowledgement.leader;
    private int lingerMs = DEFAULT_LINGER_MILLIS;
    private int retries = DEFAULT_RETRIES;

    public KafkaProducerProvider build() {
      return new KafkaProducerProvider(kafkaHosts, kafkaPort, maxRequestSize, requestTimeoutMs, maxBlockMs, sendBuffer, batchSize, compression, acknowledgements, lingerMs, retries);
    }

    public Builder setKafkaHosts(String kafkaHosts) {
      this.kafkaHosts = kafkaHosts;
      return this;
    }

    public Builder setKafkaPort(int kafkaPort) {
      this.kafkaPort = kafkaPort;
      return this;
    }

    public Builder setMaxRequestSize(int maxRequestSize) {
      this.maxRequestSize = maxRequestSize;
      return this;
    }

    public Builder setRequestTimeoutMs(int requestTimeoutMs) {
      this.requestTimeoutMs = requestTimeoutMs;
      return this;
    }

    public Builder setMaxBlockMs(int maxBlockMs) {
      this.maxBlockMs = maxBlockMs;
      return this;
    }

    public Builder setSendBuffer(int sendBuffer) {
      this.sendBuffer = sendBuffer;
      return this;
    }

    public Builder setBatchSize(int batchSize) {
      this.batchSize = batchSize;
      return this;
    }

    public Builder setCompression(Compression compression) {
      this.compression = compression;
      return this;
    }

    public Builder setAcknowledgements(Acknowledgement acknowledgements) {
      this.acknowledgements = acknowledgements;
      return this;
    }

    public Builder setLingerMs(int lingerMs) {
      this.lingerMs = lingerMs;
      return this;
    }

    public Builder setRetries(int retries) {
      this.retries = retries;
      return this;
    }
  }

}
