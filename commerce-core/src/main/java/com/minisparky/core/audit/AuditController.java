package com.minisparky.core.audit;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.minisparky.core.audit.AuditService.AuditRow;
import com.minisparky.core.auth.Caller;
import com.minisparky.core.error.ApiException;

@RestController
public class AuditController {

    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping("/api/audit")
    public List<AuditRow> get(@RequestParam String sessionId,
                              @RequestAttribute("caller") Caller caller) {
        if (!sessionId.equals(caller.sessionId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "You can only read your own session's audit trail");
        }
        return audit.forSession(sessionId);
    }
}