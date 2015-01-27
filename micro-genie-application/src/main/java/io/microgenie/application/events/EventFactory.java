package io.microgenie.application.events;

import java.io.Closeable;
import java.util.List;



/***
 * EventApi Command Factory 
 * @author shawn
 */
public abstract class EventFactory implements Closeable {


	/***
	 * Constructor
	 */
	public EventFactory(){}
	

	public abstract void publish(final Event event);
	public abstract void publish(final List<Event> events);
	
	public abstract void publish(final String clientId, final Event event);
	public abstract void publish(final String clientId, final List<Event> events);

	public abstract Publisher createPublisher(final String clientId);
	public abstract Subscriber createSubscriber(final String topic, final String clientId);
	
	
	public abstract StateChangePublisher createChangePublisher(String clientId);
	
	/***
	 * Subscribe to the given topic, providing an event handler that will process all
	 * consumed events for this Subscriber
	 * 
	 * @param topic
	 * @param clientId
	 * @param handler
	 */
	public abstract void subcribe(final String topic, final String clientId, EventHandler handler);
	

	
	/**
	 * Creates a publisher
	 * @param clientId - The clientId of the publisher to get
	 * @return publisher
	 */
	public synchronized Publisher getPublisher(final String clientId) {
		return this.createPublisher(clientId);
	}
}
