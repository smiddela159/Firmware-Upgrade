package com.att.device.management.service;

import com.att.device.management.domain.*;
import com.att.device.management.dto.*;
import com.att.device.management.repository.CampaignDeviceRepository;
import com.att.device.management.repository.CampaignRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class DeviceSessionService {

    private static final Logger log = LoggerFactory.getLogger(DeviceSessionService.class);

    private final CampaignDeviceRepository deviceRepository;
    private final CampaignRepository campaignRepository;
    private final MetricsService metricsService;

    public DeviceSessionService(CampaignDeviceRepository deviceRepository,
                                CampaignRepository campaignRepository,
                                MetricsService metricsService) {
        this.deviceRepository = deviceRepository;
        this.campaignRepository = campaignRepository;
        this.metricsService = metricsService;
    }

    /**
     * Handles POST /devices/{deviceId}/inform
     * Matches TR-069 session open. Returns commands if pending.
     */
    @Transactional
    public DeviceInformResponse handleInform(String deviceId, DeviceInformRequest request) {
        log.debug("Received inform from {}: event={}, firmwareVersion={}", deviceId, request.event(), request.firmwareVersion());

        // Find active device in flight
        List<DeviceState> inFlightStates = List.of(
                DeviceState.NOTIFIED,
                DeviceState.DOWNLOAD_SENT,
                DeviceState.ACCEPTED,
                DeviceState.REBOOTING
        );

        List<CampaignDevice> inFlightCandidates = deviceRepository.findByDeviceIdAndStateIn(deviceId, inFlightStates);

        if (inFlightCandidates.isEmpty()) {
            // Check if device is already in a terminal state (idempotency check)
            if (request.transferComplete() != null && request.transferComplete().commandKey() != null) {
                deviceRepository.findByCommandKey(request.transferComplete().commandKey())
                        .ifPresent(d -> log.info("Inform received for device {} already in terminal state {}", deviceId, d.getState()));
            }
            return new DeviceInformResponse(Collections.emptyList());
        }

        // Prefer the candidate belonging to a currently active campaign
        CampaignDevice device = inFlightCandidates.stream()
                .filter(d -> {
                    Campaign c = campaignRepository.findById(d.getCampaignId()).orElse(null);
                    return c != null && (c.getStatus() == CampaignStatus.RUNNING || c.getStatus() == CampaignStatus.PAUSED);
                })
                .findFirst()
                .orElse(inFlightCandidates.getFirst());
        Campaign campaign = campaignRepository.findById(device.getCampaignId()).orElse(null);
        if (campaign == null) {
            return new DeviceInformResponse(Collections.emptyList());
        }

        device.setCurrentVersion(request.firmwareVersion());

        // 1. Handle post-reboot TransferComplete event
        if ("TRANSFER_COMPLETE".equalsIgnoreCase(request.event()) || request.transferComplete() != null) {
            handleTransferComplete(device, campaign, request);
            deviceRepository.save(device);
            return new DeviceInformResponse(Collections.emptyList());
        }

        // 2. Handle BOOT or PERIODIC inform during/after upgrade
        if ("BOOT".equalsIgnoreCase(request.event()) || "PERIODIC".equalsIgnoreCase(request.event())) {
            if (device.getState() == DeviceState.REBOOTING || device.getState() == DeviceState.ACCEPTED) {
                handleBootEvidence(device, campaign, request.firmwareVersion());
                deviceRepository.save(device);
                return new DeviceInformResponse(Collections.emptyList());
            }
        }

        // 3. Handle wake-up callback from connection-request (NOTIFIED -> DOWNLOAD_SENT)
        if (device.getState() == DeviceState.NOTIFIED) {
            String commandKey = "cmd-" + UUID.randomUUID().toString().substring(0, 8);
            device.setCommandKey(commandKey);
            transition(device, DeviceState.DOWNLOAD_SENT);
            device.setDownloadSentAt(Instant.now());
            deviceRepository.save(device);

            DeviceCommand downloadCmd = new DeviceCommand(
                    "DOWNLOAD",
                    commandKey,
                    campaign.getImageUrl(),
                    campaign.getTargetVersion()
            );

            log.info("Issued DOWNLOAD command {} for device {}", commandKey, deviceId);
            return new DeviceInformResponse(List.of(downloadCmd));
        }

        return new DeviceInformResponse(Collections.emptyList());
    }

    /**
     * Handles POST /devices/{deviceId}/command-response
     */
    @Transactional
    public void handleCommandResponse(String deviceId, DeviceCommandResponseRequest request) {
        log.debug("Received command-response from {}: cmdKey={}, status={}", deviceId, request.commandKey(), request.status());

        if (request.commandKey() == null || request.commandKey().isBlank()) {
            log.warn("Command response received with empty commandKey for device {}", deviceId);
            return;
        }

        Optional<CampaignDevice> devOpt = deviceRepository.findByCommandKey(request.commandKey());
        if (devOpt.isEmpty()) {
            log.warn("Command response received for unknown commandKey {}", request.commandKey());
            return;
        }

        CampaignDevice device = devOpt.get();

        // Idempotency check: Ignore duplicate command responses if already advanced
        if (device.getState() == DeviceState.ACCEPTED || device.getState() == DeviceState.REBOOTING || device.getState().isTerminal()) {
            log.debug("Ignoring duplicate command-response for device {} in state {}", deviceId, device.getState());
            return;
        }

        if ("ACCEPTED".equalsIgnoreCase(request.status())) {
            transition(device, DeviceState.ACCEPTED);
            device.setAcceptedAt(Instant.now());
            // Immediately anticipate rebooting
            transition(device, DeviceState.REBOOTING);
            device.setRebootingAt(Instant.now());
            log.info("Device {} ACCEPTED download, now REBOOTING", deviceId);
        } else {
            transition(device, DeviceState.FAILED);
            device.setTerminalAt(Instant.now());
            device.setFailureReason("Command REJECTED: " + (request.reason() != null ? request.reason() : "Unknown"));
            log.warn("Device {} REJECTED download: {}", deviceId, device.getFailureReason());
        }

        deviceRepository.save(device);
    }

    private void handleTransferComplete(CampaignDevice device, Campaign campaign, DeviceInformRequest request) {
        TransferCompleteInfo tc = request.transferComplete();
        int faultCode = (tc != null) ? tc.faultCode() : 0;

        if (faultCode == 0 && campaign.getTargetVersion().equals(request.firmwareVersion())) {
            transition(device, DeviceState.SUCCEEDED);
            device.setTerminalAt(Instant.now());
            log.info("Device {} SUCCEEDED via TransferComplete", device.getDeviceId());
        } else {
            transition(device, DeviceState.FAILED);
            device.setTerminalAt(Instant.now());
            device.setFailureReason("TransferComplete faultCode: " + faultCode);
            log.warn("Device {} FAILED via TransferComplete (faultCode={})", device.getDeviceId(), faultCode);
        }
    }

    private void handleBootEvidence(CampaignDevice device, Campaign campaign, String reportedVersion) {
        // Requirement 4: Decide and document how you treat devices that come back without a TransferComplete
        if (campaign.getTargetVersion().equals(reportedVersion)) {
            transition(device, DeviceState.SUCCEEDED);
            device.setTerminalAt(Instant.now());
            log.info("Device {} SUCCEEDED via evidence-based BOOT version match ({}) without TransferComplete",
                    device.getDeviceId(), reportedVersion);
        } else {
            transition(device, DeviceState.FAILED);
            device.setTerminalAt(Instant.now());
            device.setFailureReason("Device rebooted on old firmware version: " + reportedVersion);
            log.warn("Device {} FAILED - rebooted on old firmware {}", device.getDeviceId(), reportedVersion);
        }
    }

    private void transition(CampaignDevice device, DeviceState newState) {
        metricsService.recordStateTransition(device.getState().name(), newState.name());
        device.setState(newState);
    }
}

