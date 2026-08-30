package com.mulgil.notification;

import com.mulgil.common.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
final class NotificationController {
    private final NotificationService service;

    NotificationController(NotificationService service) {
        this.service = service;
    }

    @PutMapping("/devices/fcm-token")
    NotificationService.DeviceToken register(@Valid @RequestBody DeviceTokenWriteRequest request) {
        return service.register(CurrentUser.id(), request.token(), request.platform(), request.timezone());
    }

    @DeleteMapping("/devices/fcm-token")
    ResponseEntity<Void> remove(@Valid @RequestBody DeviceTokenDeleteRequest request) {
        service.remove(CurrentUser.id(), request.token());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/notifications")
    List<NotificationService.NotificationView> list(
            @RequestParam(defaultValue = "false") boolean unreadOnly
    ) {
        return service.list(CurrentUser.id(), unreadOnly);
    }

    record DeviceTokenWriteRequest(
            @NotBlank @Size(max = 4096) @Pattern(regexp = "\\S+") String token,
            @NotBlank @Pattern(regexp = "android|ios") String platform,
            @NotBlank @Size(max = 255) String timezone
    ) {}

    record DeviceTokenDeleteRequest(
            @NotBlank @Size(max = 4096) @Pattern(regexp = "\\S+") String token
    ) {}
}
