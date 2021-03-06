package io.microgenie.aws.sqs;

import io.microgenie.application.queue.Consumer;
import io.microgenie.application.queue.Message;
import io.microgenie.application.queue.MessageHandler;
import io.microgenie.application.queue.Producer;
import io.microgenie.application.queue.QueueFactory;
import io.microgenie.application.util.CollectionUtil;
import io.microgenie.aws.admin.SqsQueueAdmin;
import io.microgenie.aws.config.SqsConfig;
import io.microgenie.aws.config.SqsConsumerConfig;
import io.microgenie.aws.config.SqsQueueConfig;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.QueueDoesNotExistException;
import com.google.common.base.Strings;
import com.google.common.collect.Maps;



/**
 * SQS Client Factory enabling common Consumer / Producer patterns with 
 * a standard message queue interface.
 * <p>
 * 
 * Queue consumers are asynchronous and multi threaded and can be configured with an
 * {@link SqsQueueConfig} instance. The SqsQueuefactory is also capable of initializing the 
 * SQS Queues at start up by invoking methods on an internal {@link SqsQueueAdmin} instance.
 * <p>
 * Calling the {@link SqsFactory#close()} method will start up background consumers. 
 * 
 * @author shawn
 */
public class SqsFactory extends QueueFactory{

	private static final Logger LOGGER = LoggerFactory.getLogger(SqsFactory.class);
	
	private final AmazonSQSClient sqs;
	private final SqsQueueAdmin admin;
	private final SqsConfig config;
	
	
	/** internal maps to store configuration for queues, consumer config and consumers **/
	private final Map<String, SqsQueueConfig> queueConfigMap = Maps.newHashMap();
	private final Map<String, SqsConsumerConfig> consumerConfigMap = Maps.newHashMap();
	private final Map<String, Consumer> consumers = Maps.newHashMap();
	
	private Producer producer;
	
	
	/**
	 * @param sqsClient
	 * @param config
	 */
	public SqsFactory(final AmazonSQSClient sqsClient, final SqsConfig config){
		
		this.sqs = sqsClient;
		this.config = config;
		this.admin = new SqsQueueAdmin(this.sqs);
		this.mapQueueConfig(config.getQueues());
		this.createConsumers(config.getConsumers());
		
		if(config.isProduces()){
			this.producer = new SqsProducer(this.sqs, admin);
		}
	}



	@Override
	public Producer getProducer() {
		return this.producer;
	}
	@Override
	public Consumer getConsumer(String queue) {
		return this.consumers.get(queue);
	}
	@Override
	public void produce(Message message) {
		this.producer.submit(message);
	}
	@Override
	public void produceBatch(List<Message> messages) {
		this.producer.submitBatch(messages);
	}
	
	
	
	/***
	 * Creates a consumer with the default SqsConfig. 
	 * <p>
	 * This method ensures that the queue exists. If it does not exist it will be created
	 * After the queue is created Consumers will be started. The number of threads determines how
	 * many consuming threads are started with the given queue
	 */
	@Override
	public synchronized void consume(final String queue, final int threads, final MessageHandler handler) {
		
		Consumer consumer = this.consumers.get(queue);
		if(consumer==null){
			this.createQueueAndConfigIfNotExists(queue, handler);
			final SqsConsumerConfig consumerConfig = new SqsConsumerConfig();
			consumerConfig.setQueue(queue);
			consumerConfig.setHandlerInstance(handler);
			consumerConfig.setThreads(threads);
			consumer = this.createAndSetConsumer(consumerConfig);
		}
		consumer.start();
	}


	


	/***
	 * Stop all consumers and close the SQS Client
	 */
	@Override
	public synchronized void close(){
		LOGGER.info("shutting down admin client");
		
		if(this.admin!=null){
			this.admin.shutdown();	
		}
		
		if(this.consumers !=null && this.consumers.size()>0){
			int i = 0;
			for(Entry<String, Consumer> c : consumers.entrySet()){
				LOGGER.info("stopping consumer: {}", ++i);
				c.getValue().stop();
				LOGGER.info("consumer[{}].isRunning() is: {}", i, c.getValue().isRunning());
			}
			this.consumers.clear();
		}
		if(this.consumerConfigMap !=null && !this.consumerConfigMap.isEmpty()){
			this.consumerConfigMap.clear();
		}
	}
	
	
	
	
	
	/***
	 * Initialize Queue Consumers, this also starts the consumers
	 * @param consumers
	 * @throws ClassNotFoundException 
	 */
	private void createConsumers(final List<SqsConsumerConfig> consumers)  {
		if(CollectionUtil.hasElements(consumers)){
			for(SqsConsumerConfig config: consumers){
				this.createAndSetConsumer(config);
			}
		}
	}

	

	/** 
	 * Create the {@link SqsConsumer} and map the consumerConfiguration
	 * @param consumerConfig
	 */
	private Consumer createAndSetConsumer(final SqsConsumerConfig consumerConfig) {
		final Consumer consumer = new SqsConsumer(this.sqs, this.admin, consumerConfig);
		this.consumers.put(consumerConfig.getQueue(), consumer);
		this.consumerConfigMap.put(consumerConfig.getQueue(), consumerConfig);
		return consumer;
	}
	



	
	
	/***
	 * Create a mapping of queue to queue configuration for lookups
	 *  
	 * @param queues
	 */
	private void mapQueueConfig(final List<SqsQueueConfig> queues) {
		if(CollectionUtil.hasElements(queues)){
			for(SqsQueueConfig config : queues){
				this.queueConfigMap.put(config.getName(), config);
			}			
		}
	}

	

	private void createQueueAndConfigIfNotExists(final String queue, final MessageHandler handler) {
		
		/** determine if the queue exists, if not, create it with the default settings **/
		try{
			final String url = this.admin.getQueueUrl(queue);
			if(Strings.isNullOrEmpty(url)){
				throw new QueueDoesNotExistException(String.format("The queue: %s was not found", queue));
			}
		}catch(QueueDoesNotExistException qneException){
			/** determine if the queue configuration exists **/
			SqsQueueConfig queueConfig = this.queueConfigMap.get(queue);
			if(queueConfig==null){
				/** create default config if we don't know about it **/
				queueConfig = new SqsQueueConfig();
				queueConfig.setName(queue);
				this.queueConfigMap.put(queue, queueConfig);
			}
			LOGGER.info("Queue: {} does not exist - creating the queue now", queue);
			this.admin.initializeQueue(queueConfig, this.config.isBlockUntilReady());
		}
	}
}
