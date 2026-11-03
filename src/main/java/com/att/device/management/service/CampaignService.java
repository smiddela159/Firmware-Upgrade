package com.att.device.management.service;

import com.att.device.management.auth.SecurityUtils;
import com.att.device.management.domain.*;
import com.att.device.management.dto.*;
import com.att.device.management.repository.CampaignDeviceRepository;
import com.att.device.management.repository.CampaignRepository;
import com.att.device.management.repository.CampaignStatusHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class CampaignService {

    private static final Logger log = LoggerFactory.getLogger(CampaignService.class);

    private final CampaignRepository campaignRepository;
    private final CampaignDeviceRepository deviceRepository;
    private final CampaignStatusHistoryRepository historyRepository;
    private final SimulatorClient simulatorClient;
    private final MetricsService metricsService;

    public CampaignService(CampaignRepository campaignRepository,
                           CampaignDeviceRepository deviceRepository,
                           CampaignStatusHistoryRepository historyRepository,
                           SimulatorClient simulatorClient,
                           MetricsService metricsService) {
        this.campaignRepository = campaignRepository;
        this.deviceRepository = deviceRepository;
        this.historyRepository = historyRepository;
        this.simulatorClient = simulatorClient;
        this.metricsService = metricsService;
    }

    @Transactional
    public CreateCampaignResponse createCampaign(CreateCampaignRequest req) {
        String campaignId = "camp-" + UUID.randomUUID().toString().substring(0, 8);
        String currentVersionFilter = req.deviceFilter() != null ? req.deviceFilter().getOrDefault("currentVersion", "1.0.0") : "1.0.0";
        String currentUser = SecurityUtils.getCurrentUser();

        Campaign campaign = new Campaign(
                campaignId,
                req.targetVersion(),
                req.imageUrl(),
                currentVersionFilter,
                req.maxConcurrent() > 0 ? req.maxConcurrent() : 50,
                req.failureThresholdPercent() > 0 ? req.failureThresholdPercent() : 15.0
        );
        campaign.setCreatedBy(currentUser);
        campaign.setLastModifiedBy(currentUser);
        campaign.setLastModifiedAt(campaign.getCreatedAt());

        campaignRepository.save(campaign);
        log.info("Created Campaign {} targeting version {} with current version {} by {}", campaignId, req.targetVersion(), currentVersionFilter, currentUser);

        // Discover fleet from simulator
        List<FleetDeviceItem> fleet = simulatorClient.discoverFleet(currentVersionFilter);
        log.info("Discovered {} devices matching current version {}", fleet.size(), currentVersionFilter);

        List<CampaignDevice> devices = new ArrayList<>();
        for (FleetDeviceItem item : fleet) {
            devices.add(new CampaignDevice(campaignId, item.deviceId(), item.firmwareVersion()));
        }
        deviceRepository.saveAll(devices);

        recordStatusTransition(campaignId, null, CampaignStatus.RUNNING, currentUser, "Campaign initiated");
        metricsService.recordCampaignStatus("RUNNING");

        return new CreateCampaignResponse(
                campaign.getId(),
                campaign.getStatus().name(),
                campaign.getTargetVersion(),
                devices.size(),
                campaign.getCreatedAt(),
                currentUser
        );
    }

    @Transactional(readOnly = true)
    public List<CampaignSummaryResponse> listCampaigns() {
        List<Campaign> campaigns = campaignRepository.findAll();
        List<CampaignSummaryResponse> summaries = new ArrayList<>();
        for (Campaign c : campaigns) {
            long total = deviceRepository.countByCampaignId(c.getId());
            long succeeded = deviceRepository.countByCampaignIdAndState(c.getId(), DeviceState.SUCCEEDED);
            long failed = deviceRepository.countByCampaignIdAndState(c.getId(), DeviceState.FAILED);
            long timedOut = deviceRepository.countByCampaignIdAndState(c.getId(), DeviceState.TIMED_OUT);
            long completedSamples = succeeded + failed + timedOut;
            double failureRate = completedSamples > 0 ? ((double) (failed + timedOut) / completedSamples) * 100.0 : 0.0;

            summaries.add(new CampaignSummaryResponse(
                    c.getId(),
                    c.getStatus().name(),
                    c.getTargetVersion(),
                    total,
                    succeeded,
                    failed,
                    timedOut,
                    Math.round(failureRate * 100.0) / 100.0,
                    c.getCreatedAt(),
                    c.getCompletedAt(),
                    c.getCreatedBy(),
                    c.getLastModifiedBy(),
                    c.getLastModifiedAt()
            ));
        }
        return summaries;
    }

    @Transactional(readOnly = true)
    public Optional<CampaignProgressResponse> getCampaignProgress(String campaignId) {
        Optional<Campaign> campOpt = campaignRepository.findById(campaignId);
        if (campOpt.isEmpty()) {
            return Optional.empty();
        }

        Campaign c = campOpt.get();
        List<CampaignDevice> devices = deviceRepository.findByCampaignId(campaignId);

        long total = devices.size();
        long inFlight = 0;
        long succeeded = 0;
        long failed = 0;
        long timedOut = 0;

        Map<String, Long> stateCounts = new LinkedHashMap<>();
        for (DeviceState s : DeviceState.values()) {
            stateCounts.put(s.name(), 0L);
        }

        for (CampaignDevice d : devices) {
            stateCounts.put(d.getState().name(), stateCounts.get(d.getState().name()) + 1);
            if (d.getState().isInFlight()) inFlight++;
            if (d.getState() == DeviceState.SUCCEEDED) succeeded++;
            if (d.getState() == DeviceState.FAILED) failed++;
            if (d.getState() == DeviceState.TIMED_OUT) timedOut++;
        }

        long completedSamples = succeeded + failed + timedOut;
        double failureRate = completedSamples > 0 ? ((double) (failed + timedOut) / completedSamples) * 100.0 : 0.0;

        Long durationSeconds = null;
        if (c.getCompletedAt() != null) {
            durationSeconds = Duration.between(c.getCreatedAt(), c.getCompletedAt()).getSeconds();
        } else {
            durationSeconds = Duration.between(c.getCreatedAt(), Instant.now()).getSeconds();
        }

        List<CampaignStatusHistory> historyEntities = historyRepository.findByCampaignIdOrderByChangedAtAsc(campaignId);
        List<StatusHistoryItemResponse> statusHistory = historyEntities.stream()
                .map(h -> new StatusHistoryItemResponse(
                        h.getFromStatus() != null ? h.getFromStatus().name() : null,
                        h.getToStatus().name(),
                        h.getChangedBy(),
                        h.getChangedAt(),
                        h.getReason()
                ))
                .toList();

        CampaignTimingMetricsResponse timingMetrics = calculateTimingMetrics(c, historyEntities, devices, durationSeconds);

        return Optional.of(new CampaignProgressResponse(
                c.getId(),
                c.getStatus().name(),
                c.getTargetVersion(),
                total,
                inFlight,
                succeeded,
                failed,
                timedOut,
                Math.round(failureRate * 100.0) / 100.0,
                stateCounts,
                c.getHaltReason(),
                c.getCreatedAt(),
                c.getCompletedAt(),
                durationSeconds,
                c.getCreatedBy(),
                c.getLastModifiedBy(),
                c.getLastModifiedAt(),
                statusHistory,
                timingMetrics
        ));
    }

    @Transactional
    public boolean pauseCampaign(String campaignId) {
        String currentUser = SecurityUtils.getCurrentUser();
        return campaignRepository.findById(campaignId).map(c -> {
            if (c.getStatus() == CampaignStatus.RUNNING) {
                c.setStatus(CampaignStatus.PAUSED);
                c.setHaltReason("Manually paused by operator");
                c.setLastModifiedBy(currentUser);
                c.setLastModifiedAt(Instant.now());
                campaignRepository.save(c);
                recordStatusTransition(campaignId, CampaignStatus.RUNNING, CampaignStatus.PAUSED, currentUser, "Manually paused by operator");
                log.info("Campaign {} paused by {}", campaignId, currentUser);
                return true;
            }
            return false;
        }).orElse(false);
    }

    @Transactional
    public boolean resumeCampaign(String campaignId) {
        String currentUser = SecurityUtils.getCurrentUser();
        return campaignRepository.findById(campaignId).map(c -> {
            if (c.getStatus() == CampaignStatus.PAUSED || c.getStatus() == CampaignStatus.HALTED) {
                CampaignStatus prevStatus = c.getStatus();
                c.setStatus(CampaignStatus.RUNNING);
                c.setHaltReason(null);
                c.setLastModifiedBy(currentUser);
                c.setLastModifiedAt(Instant.now());
                campaignRepository.save(c);
                recordStatusTransition(campaignId, prevStatus, CampaignStatus.RUNNING, currentUser, "Manually resumed by operator");
                metricsService.recordCampaignStatus("RUNNING");
                log.info("Campaign {} resumed by {}", campaignId, currentUser);
                return true;
            }
            return false;
        }).orElse(false);
    }

    @Transactional(readOnly = true)
    public Optional<CampaignDevicesPageResponse> getCampaignDevices(String campaignId, DeviceState state, Pageable pageable) {
        if (!campaignRepository.existsById(campaignId)) {
            return Optional.empty();
        }

        Page<CampaignDevice> page;
        if (state != null) {
            page = deviceRepository.findAllByCampaignIdAndState(campaignId, state, pageable);
        } else {
            page = deviceRepository.findAllByCampaignId(campaignId, pageable);
        }

        List<CampaignDeviceResponse> deviceResponses = page.getContent().stream()
                .map(d -> new CampaignDeviceResponse(
                        d.getDeviceId(),
                        d.getState().name(),
                        d.getInitialVersion(),
                        d.getCurrentVersion(),
                        d.getNudgeRetries(),
                        d.getFailureReason(),
                        d.getNotifiedAt(),
                        d.getDownloadSentAt(),
                        d.getAcceptedAt(),
                        d.getRebootingAt(),
                        d.getTerminalAt(),
                        calculateDeviceDurations(d)
                ))
                .toList();

        return Optional.of(new CampaignDevicesPageResponse(
                campaignId,
                page.getTotalElements(),
                page.getTotalPages(),
                page.getNumber(),
                page.getSize(),
                deviceResponses
        ));
    }

    @Transactional
    public void recordStatusTransition(String campaignId, CampaignStatus fromStatus, CampaignStatus toStatus,
                                       String actor, String reason) {
        CampaignStatusHistory history = new CampaignStatusHistory(
                campaignId, fromStatus, toStatus, actor, Instant.now(), reason
        );
        historyRepository.save(history);
    }

    private DeviceDurationsResponse calculateDeviceDurations(CampaignDevice d) {
        Long nudge = (d.getNotifiedAt() != null && d.getDownloadSentAt() != null)
                ? Duration.between(d.getNotifiedAt(), d.getDownloadSentAt()).getSeconds()
                : null;
        Long download = (d.getDownloadSentAt() != null && d.getAcceptedAt() != null)
                ? Duration.between(d.getDownloadSentAt(), d.getAcceptedAt()).getSeconds()
                : null;
        Long reboot = (d.getRebootingAt() != null && d.getTerminalAt() != null)
                ? Duration.between(d.getRebootingAt(), d.getTerminalAt()).getSeconds()
                : null;
        Long total = null;
        if (d.getNotifiedAt() != null) {
            Instant end = d.getTerminalAt() != null ? d.getTerminalAt() : Instant.now();
            total = Duration.between(d.getNotifiedAt(), end).getSeconds();
        }
        return new DeviceDurationsResponse(nudge, download, reboot, total);
    }

    private CampaignTimingMetricsResponse calculateTimingMetrics(
            Campaign campaign,
            List<CampaignStatusHistory> history,
            List<CampaignDevice> devices,
            Long totalElapsedSeconds) {

        long activeRunningSeconds = 0;
        long pausedSeconds = 0;

        if (!history.isEmpty()) {
            for (int i = 0; i < history.size(); i++) {
                CampaignStatusHistory current = history.get(i);
                Instant start = current.getChangedAt();
                Instant end;
                if (i + 1 < history.size()) {
                    end = history.get(i + 1).getChangedAt();
                } else {
                    end = campaign.getCompletedAt() != null ? campaign.getCompletedAt() : Instant.now();
                }
                long duration = Math.max(0, Duration.between(start, end).getSeconds());
                if (current.getToStatus() == CampaignStatus.RUNNING) {
                    activeRunningSeconds += duration;
                } else if (current.getToStatus() == CampaignStatus.PAUSED) {
                    pausedSeconds += duration;
                }
            }
        } else if (campaign.getCreatedAt() != null) {
            Instant end = campaign.getCompletedAt() != null ? campaign.getCompletedAt() : Instant.now();
            activeRunningSeconds = Math.max(0, Duration.between(campaign.getCreatedAt(), end).getSeconds());
        }

        long totalRebootSeconds = 0;
        long rebootCount = 0;
        long totalDeviceSeconds = 0;
        long terminalDeviceCount = 0;

        for (CampaignDevice d : devices) {
            if (d.getRebootingAt() != null && d.getTerminalAt() != null) {
                long rebootSec = Duration.between(d.getRebootingAt(), d.getTerminalAt()).getSeconds();
                if (rebootSec >= 0) {
                    totalRebootSeconds += rebootSec;
                    rebootCount++;
                }
            }
            if (d.getNotifiedAt() != null && d.getTerminalAt() != null) {
                long devSec = Duration.between(d.getNotifiedAt(), d.getTerminalAt()).getSeconds();
                if (devSec >= 0) {
                    totalDeviceSeconds += devSec;
                    terminalDeviceCount++;
                }
            }
        }

        Double avgReboot = rebootCount > 0
                ? Math.round(((double) totalRebootSeconds / rebootCount) * 10.0) / 10.0
                : null;
        Double avgTotalDev = terminalDeviceCount > 0
                ? Math.round(((double) totalDeviceSeconds / terminalDeviceCount) * 10.0) / 10.0
                : null;

        return new CampaignTimingMetricsResponse(
                activeRunningSeconds,
                pausedSeconds,
                totalElapsedSeconds,
                avgReboot,
                avgTotalDev
        );
    }
}

