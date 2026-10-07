package com.preclinic.backend.notification;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.preclinic.backend.common.Api;
import com.preclinic.backend.notification.NotificationService.NotificationDto;

/** Today's notifications for the caller's role. */
@RestController
@RequestMapping(Api.V1 + "/notifications")
public class NotificationController {

	public record UnreadCount(long count) {
	}

	private final NotificationService notifications;

	public NotificationController(NotificationService notifications) {
		this.notifications = notifications;
	}

	@GetMapping
	public List<NotificationDto> list(@RequestParam(defaultValue = "true") boolean unreadOnly) {
		return notifications.list(unreadOnly);
	}

	@GetMapping("/unread-count")
	public UnreadCount unreadCount() {
		return new UnreadCount(notifications.unreadCount());
	}

	@PostMapping("/{id}/read")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void markRead(@PathVariable long id) {
		notifications.markRead(id);
	}

	@PostMapping("/read-all")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void markAllRead() {
		notifications.markAllRead();
	}
}
