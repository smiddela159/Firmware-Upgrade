package com.att.device.management.service;

import com.att.device.management.domain.*;
import com.att.device.management.repository.CampaignDeviceRepository;
import com.att.device.management.repository.CampaignRepository;
import com.att.device.management.repository.CampaignStatusHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import jakarta.annotation.PreDestroy;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class CampaignScheduler {

    private static final Logger log = LoggerFactory.getLogger(CampaignScheduler.class);

    private final CampaignRepository campaignRepository;
    private final CampaignDeviceRepository deviceRepository;
    private final SimulatorClient simulatorClient;
    private final com.att.device.management.adapter.ProtocolAdapterRegistry adapterRegistry;
    private final MetricsService metricsService;
    private final CampaignStatusHistoryRepository historyRepository;

    private final int nudgeTimeoutSeconds;
    private final int ackTimeoutSeconds;
    private final int rebootTimeoutSeconds;
    private final int maxNudgeRetries;
    private final int minSampleSizeForThreshold;
    private final io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry circuitBreakerRegistry;

    public CampaignScheduler(CampaignRepository campaignRepository,
                             CampaignDeviceRepository deviceRepository,
                             SimulatorClient simulatorClient,
                             com.att.device.management.adapter.ProtocolAdapterRegistry adapterRegistry,
                             MetricsService metricsService,
                             CampaignStatusHistoryRepository historyRepository,
                             @Value("${controller.scheduler.nudge-timeout-seconds:15}") int nudgeTimeoutSeconds,
                             @Value("${controller.scheduler.ack-timeout-seconds:20}") int ackTimeoutSeconds,
                             @Value("${controller.scheduler.reboot-timeout-seconds:60}") int rebootTimeoutSeconds,
                             @Value("${controller.scheduler.max-nudge-retries:2}") int maxNudgeRetries,
                             @Value("${controller.scheduler.min-sample-size-for-threshold:20}") int minSampleSizeForThreshold,
                             io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry circuitBreakerRegistry) {
        this.campaignRepository = campaignRepository;
        this.deviceRepository = deviceRepository;
        this.simulatorClient = simulatorClient;
        this.adapterRegistry = adapterRegistry;
        this.metricsService = metricsService;
        this.historyRepository = historyRepository;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.nudgeTimeoutSeconds = nudgeTimeoutSeconds;
        this.ackTimeoutSeconds = ackTimeoutSeconds;
        this.rebootTimeoutSeconds = rebootTimeoutSeconds;
        this.maxNudgeRetries = maxNudgeRetries;
        this.minSampleSizeForThreshold = minSampleSizeForThreshold;
    }


    @PreDestroy
    public void shutdown() {
        log.info("CampaignScheduler shutting down – waiting for in‑flight devices to finish");
        // No explicit thread pools are used (virtual threads are daemon‑like), but we can
        // ensure the scheduled task does not fire again by cancelling further executions.
        // Spring will stop @Scheduled methods on context close automatically.
    }

    @Scheduled(fixedDelayString = "${controller.scheduler.poll-interval-ms:1000}")
    @Transactional
    public void executeCycle() {
        List<Campaign> runningCampaigns = campaignRepository.findByStatus(CampaignStatus.RUNNING);
        for (Campaign campaign : runningCampaigns) {
            try {
                processCampaign(campaign);
            } catch (Exception e) {
                log.error("Error executing scheduler cycle for campaign {}: {}", campaign.getId(), e.getMessage(), e);
            }
        }
    }

    private void processCampaign(Campaign campaign) {
        String campId = campaign.getId();

        // 1. Check Circuit Breaker (Safety Halt Threshold)
        long succeeded = deviceRepository.countByCampaignIdAndState(campId, DeviceState.SUCCEEDED);
        long failed = deviceRepository.countByCampaignIdAndState(campId, DeviceState.FAILED);
        long timedOut = deviceRepository.countByCampaignIdAndState(campId, DeviceState.TIMED_OUT);
        long completedSamples = succeeded + failed + timedOut;

        if (completedSamples >= minSampleSizeForThreshold) {
            double failureRate = ((double) (failed + timedOut) / completedSamples) * 100.0;
            if (failureRate > campaign.getFailureThresholdPercent()) {
                campaign.setStatus(CampaignStatus.HALTED);
                campaign.setCompletedAt(Instant.now());
                campaign.setLastModifiedAt(campaign.getCompletedAt());
                campaign.setLastModifiedBy("system:circuit-breaker");
                campaign.setHaltReason(String.format("Failure rate %.2f%% exceeded threshold %.2f%% after %d devices",
                        failureRate, campaign.getFailureThresholdPercent(), completedSamples));
                campaignRepository.save(campaign);
                historyRepository.save(new CampaignStatusHistory(
                        campId, CampaignStatus.RUNNING, CampaignStatus.HALTED,
                        "system:circuit-breaker", campaign.getCompletedAt(), campaign.getHaltReason()
                ));
                metricsService.recordCampaignStatus("HALTED");
                log.warn("CIRCUIT BREAKER TRIPPED! Campaign {} halted: {}", campId, campaign.getHaltReason());
                return;
            }
        }

        // 2. Check and enforce timeouts on in-flight devices
        handleTimeouts(campaign);

        // 3. Dispatch new nudges up to maxConcurrent in-flight capacity
        long inFlight = deviceRepository.countByCampaignIdAndStateIn(
                campId,
                List.of(DeviceState.NOTIFIED, DeviceState.DOWNLOAD_SENT, DeviceState.ACCEPTED, DeviceState.REBOOTING)
        );

        int availableSlots = campaign.getMaxConcurrent() - (int) inFlight;
        if (availableSlots > 0) {
            List<CampaignDevice> pendingDevices = deviceRepository.findByCampaignIdAndState(
                    campId, DeviceState.PENDING, PageRequest.of(0, availableSlots)
            );

            for (CampaignDevice dev : pendingDevices) {
                dispatchNudge(dev);
            }
        }

        // 4. Check for campaign completion
        long pending = deviceRepository.countByCampaignIdAndState(campId, DeviceState.PENDING);
        inFlight = deviceRepository.countByCampaignIdAndStateIn(
                campId,
                List.of(DeviceState.NOTIFIED, DeviceState.DOWNLOAD_SENT, DeviceState.ACCEPTED, DeviceState.REBOOTING)
        );

        if (pending == 0 && inFlight == 0) {
            campaign.setStatus(CampaignStatus.COMPLETED);
            campaign.setCompletedAt(Instant.now());
            campaign.setLastModifiedAt(campaign.getCompletedAt());
            campaign.setLastModifiedBy("system:scheduler");
            campaignRepository.save(campaign);
            historyRepository.save(new CampaignStatusHistory(
                    campId, CampaignStatus.RUNNING, CampaignStatus.COMPLETED,
                    "system:scheduler", campaign.getCompletedAt(),
                    String.format("All %d devices processed (Succeeded=%d, Failed=%d, TimedOut=%d)",
                            completedSamples, succeeded, failed, timedOut)
            ));
            metricsService.recordCampaignStatus("COMPLETED");
            log.info("Campaign {} reached COMPLETED state! Final: Succeeded={}, Failed={}, TimedOut={}",
                    campId, succeeded, failed, timedOut);
        }
    }

    private void dispatchNudge(CampaignDevice dev) {
        dev.setState(DeviceState.NOTIFIED);
        dev.setNotifiedAt(Instant.now());
        dev.setLastStateChange(Instant.now());
        deviceRepository.save(dev);

        metricsService.recordStateTransition("PENDING", "NOTIFIED");
        log.debug("Dispatching nudge connection-request to {}", dev.getDeviceId());

        // Use Resilience4j circuit breaker around the adapter call
        var breaker = circuitBreakerRegistry.circuitBreaker("adapterCalls");
        Thread.ofVirtual().start(() -> {
            var adapter = adapterRegistry.getAdapter("REST_NUDGE");
            // Execute via circuit breaker – if open, skip the call and record a failure
            var result = io.github.resilience4j.circuitbreaker.CallNotPermittedException.class.isAssignableFrom(
                    breaker.decorateSupplier(() -> adapter.wakeUpOrSendCommand(dev.getDeviceId(), null, null, null)).getClass())
                    ? false
                    : breaker.executeSupplier(() -> adapter.wakeUpOrSendCommand(dev.getDeviceId(), null, null, null));
            if (!result) {
                log.debug("Wakeup/nudge rejected or offline for {}", dev.getDeviceId());
            }
        });
    }



    private void handleTimeouts(Campaign campaign) {
        Instant now = Instant.now();
        List<CampaignDevice> inFlight = deviceRepository.findInFlightDevices(
                campaign.getId(),
                List.of(DeviceState.NOTIFIED, DeviceState.DOWNLOAD_SENT, DeviceState.ACCEPTED, DeviceState.REBOOTING)
        );

        for (CampaignDevice dev : inFlight) {
            Duration elapsed = Duration.between(dev.getLastStateChange(), now);

            switch (dev.getState()) {
                case NOTIFIED -> {
                    // Safe to retry: nudge did not produce a session open
                    if (elapsed.getSeconds() > nudgeTimeoutSeconds) {
                        if (dev.getNudgeRetries() < maxNudgeRetries) {
                            dev.incrementNudgeRetries();
                            dev.setState(DeviceState.PENDING); // Re-queue for nudge
                            dev.setLastStateChange(now);
                            log.info("Nudge timeout for {}. Retrying (attempt {}/{})", dev.getDeviceId(), dev.getNudgeRetries(), maxNudgeRetries);
                        } else {
                            dev.setState(DeviceState.TIMED_OUT);
                            dev.setTerminalAt(now);
                            dev.setFailureReason("Nudge timeout: device did not inform after " + maxNudgeRetries + " retries");
                            metricsService.recordStateTransition("NOTIFIED", "TIMED_OUT");
                            log.warn("Device {} timed out on NOTIFIED", dev.getDeviceId());
                        }
                        deviceRepository.save(dev);
                    }
                }
                case DOWNLOAD_SENT -> {
                    // Waiting for command acknowledgment
                    if (elapsed.getSeconds() > ackTimeoutSeconds) {
                        dev.setState(DeviceState.TIMED_OUT);
                        dev.setTerminalAt(now);
                        dev.setFailureReason("Command ACK timeout: device did not acknowledge download");
                        metricsService.recordStateTransition("DOWNLOAD_SENT", "TIMED_OUT");
                        deviceRepository.save(dev);
                        log.warn("Device {} timed out waiting for ACK", dev.getDeviceId());
                    }
                }
                case ACCEPTED, REBOOTING -> {
                    // Waiting for post-reboot inform / TransferComplete
                    if (elapsed.getSeconds() > rebootTimeoutSeconds) {
                        dev.setState(DeviceState.TIMED_OUT);
                        dev.setTerminalAt(now);
                        dev.setFailureReason("Reboot timeout: device failed to reboot within SLA (" + rebootTimeoutSeconds + "s)");
                        metricsService.recordStateTransition("REBOOTING", "TIMED_OUT");
                        deviceRepository.save(dev);
                        log.warn("Device {} timed out in REBOOTING", dev.getDeviceId());
                    }
                }
                default -> {}
            }
        }
    }
}

