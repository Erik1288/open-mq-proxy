package com.owl.kafka.client.consumer;

import com.owl.kafka.client.consumer.exceptions.TopicNotExistException;
import com.owl.kafka.client.consumer.listener.AcknowledgeMessageListener;
import com.owl.kafka.client.consumer.listener.AutoCommitMessageListener;
import com.owl.kafka.client.consumer.listener.BatchAcknowledgeMessageListener;
import com.owl.kafka.client.consumer.listener.MessageListener;
import com.owl.kafka.client.consumer.service.BatchAcknowledgeMessageListenerService;
import com.owl.kafka.client.consumer.service.MessageListenerService;
import com.owl.kafka.client.consumer.service.MessageListenerServiceRegistry;
import com.owl.kafka.client.metric.MonitorImpl;
import com.owl.kafka.client.proxy.DefaultPullMessageImpl;
import com.owl.kafka.client.proxy.DefaultPushMessageImpl;
import com.owl.kafka.client.proxy.zookeeper.KafkaZookeeperConfig;
import com.owl.kafka.client.serializer.Serializer;
import com.owl.kafka.client.util.CollectionUtils;
import com.owl.kafka.client.util.Constants;
import com.owl.kafka.client.util.Preconditions;
import com.owl.kafka.client.util.StringUtils;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @Author: Tboy
 */
@SuppressWarnings("all")
public class DefaultKafkaConsumerImpl<K, V> implements Runnable, com.owl.kafka.client.consumer.KafkaConsumer<K, V> {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultKafkaConsumerImpl.class);

    private final AtomicBoolean start = new AtomicBoolean(false);

    private Consumer<byte[], byte[]> consumer;

    private MessageListener<K, V> messageListener;

    private final Thread worker = new Thread(this, "consumer-poll-worker");

    private final ConsumerConfig configs;

    private MessageListenerService messageListenerService;

    private Serializer keySerializer;

    private Serializer valueSerializer;

    private MessageListenerServiceRegistry serviceRegistry;

    private DefaultPushMessageImpl defaultPushMessageImpl;

    private DefaultPullMessageImpl defaultPullMessageImpl;

    public DefaultKafkaConsumerImpl(com.owl.kafka.client.consumer.ConsumerConfig configs) {
        this.configs = configs;
        keySerializer = configs.getKeySerializer();
        valueSerializer = configs.getValueSerializer();

        if(!this.configs.isUseProxy()){
            // KAFKA 0.11 later version.
            if(configs.get("partition.assignment.strategy") == null){
                configs.put("partition.assignment.strategy", "com.owl.kafka.client.consumer.assignor.CheckTopicStickyAssignor");
            }
            String bootstrapServers = configs.getKafkaServers();
            if(StringUtils.isBlank(bootstrapServers)){
                bootstrapServers = KafkaZookeeperConfig.getBrokerIds(configs.getZookeeperServers(), configs.getZookeeperNamespace());
            }

            configs.put("bootstrap.servers", bootstrapServers);
            configs.put("group.id", configs.getGroupId());

            this.consumer = new org.apache.kafka.clients.consumer.KafkaConsumer(configs, new ByteArrayDeserializer(), new ByteArrayDeserializer());
        }

    }

    @Override
    public void start() {
        Preconditions.checkArgument(keySerializer != null , "keySerializer should not be null");
        Preconditions.checkArgument(valueSerializer != null , "valueSerializer should not be null");
        Preconditions.checkArgument(messageListener != null , "messageListener should not be null");
        Preconditions.checkArgument(messageListenerService != null, "messageListener implementation error");

        Preconditions.checkArgument(configs.getAcknowledgeCommitBatchSize() > 0, "AcknowledgeCommitBatchSize should be greater than 0");
        Preconditions.checkArgument(configs.getBatchConsumeSize() > 0, "BatchConsumeSize should be greater than 0");


        boolean useProxy = configs.isUseProxy();

        if (start.compareAndSet(false, true)) {
            if(useProxy){
                Preconditions.checkArgument(messageListener instanceof AcknowledgeMessageListener, "using proxy, MessageListener must be AcknowledgeMessageListener");
                if(ConsumerConfig.ProxyModel.PULL == configs.getProxyModel()){
                    defaultPullMessageImpl = new DefaultPullMessageImpl(messageListenerService);
                    defaultPullMessageImpl.start();
                } else{
                    defaultPushMessageImpl = new DefaultPushMessageImpl(messageListenerService);
                    defaultPushMessageImpl.start();
                }
            } else{
                boolean isAssignTopicPartition = !CollectionUtils.isEmpty(configs.getTopicPartitions());
                if(isAssignTopicPartition){
                    Collection<com.owl.kafka.client.consumer.TopicPartition> assignTopicPartitions = configs.getTopicPartitions();
                    ArrayList<TopicPartition> topicPartitions = new ArrayList<>(assignTopicPartitions.size());
                    for(com.owl.kafka.client.consumer.TopicPartition topicPartition : assignTopicPartitions){
                        topicPartitions.add(new TopicPartition(topicPartition.getTopic(), topicPartition.getPartition()));
                    }
                    consumer.assign(topicPartitions);
                } else{
                    if (messageListenerService instanceof ConsumerRebalanceListener) {
                        consumer.subscribe(Arrays.asList(configs.getTopic()), (ConsumerRebalanceListener) messageListenerService);
                    } else {
                        consumer.subscribe(Arrays.asList(configs.getTopic()));
                    }
                }
                //
                worker.setDaemon(true);
                worker.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                    public void uncaughtException(Thread t, Throwable e) {
                        LOG.error("Uncaught exceptions in " + worker.getName() + ": ", e);
                    }
                });
                worker.start();
                //
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> close()));

            LOG.info("kafka consumer startup with info : {}", startupInfo());
        }
    }

    @Override
    public Record<byte[], byte[]> view(long msgId) {
        if(configs.isUseProxy()){
            return defaultPullMessageImpl.view(msgId);
        } else{
            throw new UnsupportedOperationException("only proxy model can view the DLQ message");
        }
    }

    @Override
    public void setMessageListener(final MessageListener<K, V> messageListener) {
        if(this.messageListener != null){
            throw new IllegalArgumentException("messageListener has already set");
        }
        if (configs.isAutoCommit()
                && (messageListener instanceof AcknowledgeMessageListener)) {
            throw new IllegalArgumentException("AcknowledgeMessageListener must be mannual commit");
        }

        if (configs.isAutoCommit()
                && (messageListener instanceof BatchAcknowledgeMessageListener)) {
            throw new IllegalArgumentException("BatchAcknowledgeMessageListener must be mannual commit");
        }

        if (messageListener instanceof BatchAcknowledgeMessageListener && configs.isPartitionOrderly()) {
            throw new IllegalArgumentException("BatchAcknowledgeMessageListener not support partitionOrderly ");
        }

        if (!configs.isAutoCommit()
                && (messageListener instanceof AutoCommitMessageListener)) {
            throw new IllegalArgumentException("AutoCommitMessageListener must be auto commit");
        }

        System.setProperty(Constants.PROXY_MODEL, configs.getProxyModel().name());
        //
        this.serviceRegistry = new MessageListenerServiceRegistry(this, messageListener);
        this.messageListenerService = this.serviceRegistry.getMessageListenerService(false);
        this.messageListener = messageListener;
    }

    public MessageListener<K, V> getMessageListener() {
        return messageListener;
    }

    @Override
    public void run() {
        LOG.info(worker.getName() + " start.");
        while (start.get()) {
            long now = System.currentTimeMillis();
            ConsumerRecords<byte[], byte[]> records = null;
            try {
                synchronized (consumer) {
                    records = consumer.poll(configs.getPollTimeout());
                }
            } catch (TopicNotExistException ex){
                StringBuilder builder = new StringBuilder(100);
                builder.append("topic not exist, will close the consumer instance in case of the scenario : ");
                builder.append("using the same groupId for subscribe more than one topic, and one of the topic does not create in the broker, ");
                builder.append("so it will cause the other one consumer in rebalance status for at least 5 minutes due to the kafka inner config.");
                builder.append("To avoid this problem, close the consumer will speed up the rebalancing time");
                LOG.error(builder.toString(), ex);
                close();
            }
            MonitorImpl.getDefault().recordConsumePollTime(System.currentTimeMillis() - now);
            MonitorImpl.getDefault().recordConsumePollCount(1);

            if (LOG.isTraceEnabled() && records != null && !records.isEmpty()) {
                LOG.trace("Received: " + records.count() + " records");
            }
            if (records != null && !records.isEmpty()) {
                invokeMessageService(records);
            } else if(records != null && messageListener instanceof BatchAcknowledgeMessageListener){
                messageListenerService.onMessage(BatchAcknowledgeMessageListenerService.EmptyConsumerRecord.EMPTY);
            }
        }
        LOG.info(worker.getName() + " stop.");
    }

    public void commit(Map<TopicPartition, OffsetAndMetadata> highestOffsetRecords) {
        synchronized (consumer) {
            consumer.commitAsync(highestOffsetRecords, new OffsetCommitCallback() {
                @Override
                public void onComplete(Map<TopicPartition, OffsetAndMetadata> offsets, Exception exception) {
                    if(exception != null){
                        LOG.warn("commit async fail, metadata {}, exceptions {}", offsets, exception);
                    }
                }
            });
            if(LOG.isDebugEnabled()){
                LOG.debug("commit offset : {}", highestOffsetRecords);
            }
        }
    }

    private void invokeMessageService(ConsumerRecords<byte[], byte[]> records) {
        Iterator<ConsumerRecord<byte[], byte[]>> iterator = records.iterator();
        while (iterator.hasNext()) {
            ConsumerRecord<byte[], byte[]> record = iterator.next();
            if (LOG.isTraceEnabled()) {
                LOG.trace("Processing " + record);
            }
            MonitorImpl.getDefault().recordConsumeRecvCount(1);

            try {
                messageListenerService.onMessage(record);
            } catch (Throwable ex) {
                LOG.error("onMessage error", ex);
            }
        }
    }

    public ConsumerConfig getConfigs() {
        return configs;
    }

    public Record toRecord(ConsumerRecord<byte[], byte[]> record) {
        byte[] keyBytes = record.key();
        byte[] valueBytes = record.value();


        return new Record(record.offset(), record.topic(), record.partition(), record.offset(),
                keyBytes != null ? (K) keySerializer.deserialize(record.key(), Object.class) : null,
                valueBytes != null ? (V) valueSerializer.deserialize(record.value(), Object.class) : null,
                record.timestamp());
    }

    @Override
    public void close() {
        if(start.compareAndSet(true, false)){
            LOG.info("KafkaConsumer closing.");
            if(consumer != null){
                synchronized (consumer) {
                    if (messageListenerService != null) {
                        messageListenerService.close();
                    }
                    consumer.unsubscribe();
                    consumer.close();
                }
            }
            if(defaultPushMessageImpl != null){
                defaultPushMessageImpl.close();
            }
            if(defaultPullMessageImpl != null){
                defaultPullMessageImpl.close();
            }
            LOG.info("KafkaConsumer closed.");
        }
    }

    /**
     * 启动信息，方便日后排查问题
     * @return
     */
    private String startupInfo(){
        boolean isAssignTopicPartition = !CollectionUtils.isEmpty(configs.getTopicPartitions());
        StringBuilder builder = new StringBuilder(200);
        builder.append("bootstrap.servers : ").append(StringUtils.isBlank(configs.getKafkaServers()) ? configs.getZookeeperServers() : configs.getKafkaServers()).append(" , ");
        builder.append("group.id : ").append(configs.getGroupId()).append(" , ");
        builder.append("in ").append(isAssignTopicPartition ? "[assign] : " + configs.getTopicPartitions(): "[subscribe] : " + configs.getTopic()).append(" , ");
        builder.append("with : " + (configs.isUseProxy() ?  "proxy model " : " direct connect ")).append(" , ");
        builder.append("with listener : " + messageListener.getClass().getName()).append(" , ");
        builder.append("with listener service : " + messageListenerService.getClass().getSimpleName()).append(" ");
        return builder.toString();
    }
}
