package com.healthrecon.rag.api;

import com.healthrecon.rag.api.dto.QuotaResponse;
import com.healthrecon.rag.security.CurrentUser;
import com.healthrecon.rag.security.CurrentUserSupport;
import com.healthrecon.rag.service.QuotaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quota")
public class QuotaController {

    private final QuotaService quotaService;

    public QuotaController(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    @GetMapping
    public QuotaResponse get() {
        CurrentUser user = CurrentUserSupport.require();
        QuotaService.QuotaSnapshot snapshot = quotaService.snapshot(user.id());
        return new QuotaResponse(snapshot.usedBytes(), snapshot.limitBytes());
    }
}