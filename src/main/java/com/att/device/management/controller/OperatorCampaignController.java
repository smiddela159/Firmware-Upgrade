package com.att.device.management.controller;

import com.att.device.management.domain.DeviceState;
import com.att.device.management.dto.*;
import com.att.device.management.service.CampaignService;
import com.att.device.management.service.MetricsService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/campaigns")
public class OperatorCampaignController {

    private final CampaignService campaignService;
    private final MetricsService metricsService;

    public OperatorCampaignController(CampaignService campaignService, MetricsService metricsService) {
        this.campaignService = campaignService;
        this.metricsService = metricsService;
    }

    /**
     * POST /campaigns
     * Creates a new firmware upgrade campaign.
     * Requires Idempotency-Key header and OAuth2 token with campaign.write scope.
     * Body: { targetVersion, imageUrl, deviceFilter: { currentVersion }, maxConcurrent, failureThresholdPercent }
     */
    @PostMapping
    public ResponseEntity<CreateCampaignResponse> createCampaign(@Valid @RequestBody CreateCampaignRequest request) {
        CreateCampaignResponse response = campaignService.createCampaign(request);
        metricsService.recordHttpRequest("POST", "/campaigns", 201);

        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.campaignId())
                .toUri();

        return ResponseEntity.created(location).body(response);
    }

    /**
     * GET /campaigns
     * Lists all campaigns with summary status.
     */
    @GetMapping
    public ResponseEntity<List<CampaignSummaryResponse>> listCampaigns() {
        List<CampaignSummaryResponse> campaigns = campaignService.listCampaigns();
        metricsService.recordHttpRequest("GET", "/campaigns", 200);
        return ResponseEntity.ok(campaigns);
    }

    /**
     * GET /campaigns/{id}
     * Returns detailed progress: counts per device state, failure rate and campaign status.
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getCampaign(@PathVariable("id") String campaignId) {
        return campaignService.getCampaignProgress(campaignId)
                .map(progress -> {
                    metricsService.recordHttpRequest("GET", "/campaigns/{id}", 200);
                    return ResponseEntity.ok((Object) progress);
                })
                .orElseGet(() -> {
                    metricsService.recordHttpRequest("GET", "/campaigns/{id}", 404);
                    return ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(new ErrorResponse(404, "Not Found",
                                    "Campaign not found: " + campaignId));
                });
    }

    /**
     * GET /campaigns/{id}/devices
     * Returns individual devices associated with the campaign with optional state filter and pagination.
     */
    @GetMapping("/{id}/devices")
    public ResponseEntity<?> getCampaignDevices(
            @PathVariable("id") String campaignId,
            @RequestParam(value = "state", required = false) DeviceState state,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 200), Sort.by("id").ascending());
        return campaignService.getCampaignDevices(campaignId, state, pageable)
                .map(response -> {
                    metricsService.recordHttpRequest("GET", "/campaigns/{id}/devices", 200);
                    return ResponseEntity.ok((Object) response);
                })
                .orElseGet(() -> {
                    metricsService.recordHttpRequest("GET", "/campaigns/{id}/devices", 404);
                    return ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(new ErrorResponse(404, "Not Found",
                                    "Campaign not found: " + campaignId));
                });
    }

    /**
     * POST /campaigns/{id}/pause
     * Pauses a running campaign. No new device nudges will be dispatched.
     */
    @PostMapping("/{id}/pause")
    public ResponseEntity<?> pauseCampaign(@PathVariable("id") String campaignId) {
        boolean ok = campaignService.pauseCampaign(campaignId);
        metricsService.recordHttpRequest("POST", "/campaigns/{id}/pause", ok ? 200 : 409);
        if (ok) {
            return campaignService.getCampaignProgress(campaignId)
                    .map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        } else {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse(409, "Conflict",
                            "Campaign not found or not in RUNNING state"));
        }
    }

    /**
     * POST /campaigns/{id}/resume
     * Resumes a paused or halted campaign.
     */
    @PostMapping("/{id}/resume")
    public ResponseEntity<?> resumeCampaign(@PathVariable("id") String campaignId) {
        boolean ok = campaignService.resumeCampaign(campaignId);
        metricsService.recordHttpRequest("POST", "/campaigns/{id}/resume", ok ? 200 : 409);
        if (ok) {
            return campaignService.getCampaignProgress(campaignId)
                    .map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        } else {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse(409, "Conflict",
                            "Campaign not found or not in PAUSED/HALTED state"));
        }
    }
}

