package com.preclinic.backend.event;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live "something changed" signals pushed to every browser of a clinic (the assistant's and the
 * doctor's screens). An event only says WHAT changed; the browser then refetches that resource, so
 * the REST endpoints stay the single source of truth.
 *
 * Events are sent after the database commit: a client told "the queue changed" must be able to read
 * the new queue straight away, and a rolled-back change must announce nothing.
 */
@Component
public class EventBus {

	public enum Type {
		QUEUE("queue"),
		APPOINTMENTS("appointments"),
		NOTIFICATION("notification"),
		DOCTOR_STATUS("doctor-status"),
		INVOICE("invoice"),
		CONSULTATION("consultation"),
		FOLLOW_UP("follow-up");

		private final String wireName;

		Type(String wireName) {
			this.wireName = wireName;
		}

		public String wireName() {
			return wireName;
		}
	}

	private static final Logger log = LoggerFactory.getLogger(EventBus.class);

	private final Map<Long, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();

	/** Opens a stream for one browser tab of the clinic. It stays open until the client goes away. */
	public SseEmitter subscribe(long clinicId) {
		SseEmitter emitter = new SseEmitter(0L);
		Set<SseEmitter> clinicSubscribers = subscribers.computeIfAbsent(clinicId, id -> new CopyOnWriteArraySet<>());
		clinicSubscribers.add(emitter);
		Runnable remove = () -> clinicSubscribers.remove(emitter);
		emitter.onCompletion(remove);
		emitter.onTimeout(remove);
		emitter.onError(e -> remove.run());
		try {
			emitter.send(SseEmitter.event().name("connected").data("{}"));
		}
		catch (IOException e) {
			remove.run();
		}
		return emitter;
	}

	/** Announces a change to the clinic's connected browsers once the current transaction (if any) commits. */
	public void publish(long clinicId, Type type) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					broadcast(clinicId, type);
				}
			});
		}
		else {
			broadcast(clinicId, type);
		}
	}

	private void broadcast(long clinicId, Type type) {
		Set<SseEmitter> clinicSubscribers = subscribers.get(clinicId);
		if (clinicSubscribers == null) {
			return;
		}
		for (SseEmitter emitter : clinicSubscribers) {
			try {
				emitter.send(SseEmitter.event().name(type.wireName()).data("{}"));
			}
			catch (IOException | IllegalStateException e) {
				clinicSubscribers.remove(emitter);
			}
		}
	}

	/** Keeps idle connections alive through proxies and lets us notice closed browsers. */
	@Scheduled(fixedDelay = 20_000)
	void heartbeat() {
		subscribers.forEach((clinicId, clinicSubscribers) -> {
			for (SseEmitter emitter : clinicSubscribers) {
				try {
					emitter.send(SseEmitter.event().comment("ping"));
				}
				catch (IOException | IllegalStateException e) {
					clinicSubscribers.remove(emitter);
				}
			}
		});
		if (log.isTraceEnabled()) {
			log.trace("SSE subscribers: {}", subscribers.values().stream().mapToInt(Set::size).sum());
		}
	}

	int subscriberCount(long clinicId) {
		Set<SseEmitter> clinicSubscribers = subscribers.get(clinicId);
		return clinicSubscribers == null ? 0 : clinicSubscribers.size();
	}
}
