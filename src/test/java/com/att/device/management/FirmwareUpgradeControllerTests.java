package com.att.device.management;

import com.att.device.management.domain.Campaign;
import com.att.device.management.domain.CampaignDevice;
import com.att.device.management.domain.CampaignStatus;
import com.att.device.management.domain.DeviceState;
import com.att.device.management.dto.*;
import com.att.device.management.repository.CampaignDeviceRepository;
import com.att.device.management.repository.CampaignRepository;
import com.att.device.management.service.CampaignScheduler;
import com.att.device.management.service.CampaignService;
import com.att.device.management.service.DeviceSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class FirmwareUpgradeControllerTests {

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private CampaignDeviceRepository deviceRepository;

    @Autowired
    private CampaignService campaignService;

    @Autowired
    private DeviceSessionService sessionService;

    @Autowired
    private CampaignScheduler campaignScheduler;

    @BeforeEach
    void setUp() {
        deviceRepository.deleteAll();
        campaignRepository.deleteAll();
    }

    @Test
    @DisplayName("Branch: Campaign creation, progress reporting, pause and resume")
    void testCampaignCreationAndLifecycle() {
        CreateCampaignRequest req = new CreateCampaignRequest(
                "2.0.0",
                "http://cdn.broadband.att.com/images/gw-2.0.0.bin",
                Map.of("currentVersion", "1.0.0"),
                50,
                15.0
        );

        CreateCampaignResponse campaign = campaignService.createCampaign(req);
        assertNotNull(campaign.campaignId());
        assertEquals("2.0.0", campaign.targetVersion());
        assertEquals("RUNNING", campaign.status());

        Optional<CampaignProgressResponse> progress = campaignService.getCampaignProgress(campaign.campaignId());
        assertTrue(progress.isPresent());
        assertEquals("RUNNING", progress.get().status());

        // Test Pause
        boolean pauseResult = campaignService.pauseCampaign(campaign.campaignId());
        assertTrue(pauseResult);
        assertEquals(CampaignStatus.PAUSED, campaignRepository.findById(campaign.campaignId()).orElseThrow().getStatus());

        // Test Resume
        boolean resumeResult = campaignService.resumeCampaign(campaign.campaignId());
        assertTrue(resumeResult);
        assertEquals(CampaignStatus.RUNNING, campaignRepository.findById(campaign.campaignId()).orElseThrow().getStatus());

        Optional<CampaignProgressResponse> progressAfterResume = campaignService.getCampaignProgress(campaign.campaignId());
        assertTrue(progressAfterResume.isPresent());
        CampaignProgressResponse pr = progressAfterResume.get();
        assertEquals(3, pr.statusHistory().size());
        assertEquals("RUNNING", pr.statusHistory().get(0).toStatus());
        assertEquals("PAUSED", pr.statusHistory().get(1).toStatus());
        assertEquals("RUNNING", pr.statusHistory().get(2).toStatus());
        assertNotNull(pr.timingMetrics());

        // Negative: Resume non-existent campaign
        assertFalse(campaignService.resumeCampaign("invalid-camp-id"));
        assertFalse(campaignService.pauseCampaign("invalid-camp-id"));
    }

    @Test
    @DisplayName("Branch: Standard happy path with valid TransferComplete (faultCode=0)")
    void testStandardUpgradeFlowWithTransferComplete() {
        String campId = "camp-test-1";
        Campaign camp = new Campaign(campId, "2.0.0", "http://cdn/image.bin", "1.0.0", 10, 20.0);
        campaignRepository.save(camp);

        CampaignDevice dev = new CampaignDevice(campId, "gw-00001", "1.0.0");
        dev.setState(DeviceState.NOTIFIED);
        deviceRepository.save(dev);

        // Step 1: Inform CONNECTION_REQUEST -> DOWNLOAD command issued
        DeviceInformRequest informReq = new DeviceInformRequest("gw-00001", "1.0.0", "CONNECTION_REQUEST", null);
        DeviceInformResponse informResp = sessionService.handleInform("gw-00001", informReq);

        assertNotNull(informResp.commands());
        assertEquals(1, informResp.commands().size());
        DeviceCommand cmd = informResp.commands().getFirst();
        assertEquals("DOWNLOAD", cmd.type());
        assertEquals("2.0.0", cmd.targetVersion());
        String commandKey = cmd.commandKey();
        assertNotNull(commandKey);

        CampaignDevice updatedDev = deviceRepository.findByCommandKey(commandKey).orElseThrow();
        assertEquals(DeviceState.DOWNLOAD_SENT, updatedDev.getState());

        // Step 2: Device responds ACCEPTED -> moves to REBOOTING
        DeviceCommandResponseRequest cmdResp = new DeviceCommandResponseRequest(commandKey, "ACCEPTED", null);
        sessionService.handleCommandResponse("gw-00001", cmdResp);

        updatedDev = deviceRepository.findByCommandKey(commandKey).orElseThrow();
        assertEquals(DeviceState.REBOOTING, updatedDev.getState());

        // Step 3: Device informs TransferComplete with faultCode 0 -> SUCCEEDED
        TransferCompleteInfo tcInfo = new TransferCompleteInfo(commandKey, 0, System.currentTimeMillis() - 5000, System.currentTimeMillis());
        DeviceInformRequest tcReq = new DeviceInformRequest("gw-00001", "2.0.0", "TRANSFER_COMPLETE", tcInfo);
        sessionService.handleInform("gw-00001", tcReq);

        updatedDev = deviceRepository.findByCommandKey(commandKey).orElseThrow();
        assertEquals(DeviceState.SUCCEEDED, updatedDev.getState());
        assertEquals("2.0.0", updatedDev.getCurrentVersion());
    }

    @Test
    @DisplayName("Branch (Requirement 4): Evidence-based success via BOOT without TransferComplete")
    void testEvidenceBasedUpgradeFlowWithoutTransferComplete() {
        String campId = "camp-test-2";
        Campaign camp = new Campaign(campId, "2.0.0", "http://cdn/image.bin", "1.0.0", 10, 20.0);
        campaignRepository.save(camp);

        CampaignDevice dev = new CampaignDevice(campId, "gw-00002", "1.0.0");
        dev.setState(DeviceState.NOTIFIED);
        deviceRepository.save(dev);

        // Inform -> Download
        DeviceInformRequest informReq = new DeviceInformRequest("gw-00002", "1.0.0", "CONNECTION_REQUEST", null);
        DeviceInformResponse informResp = sessionService.handleInform("gw-00002", informReq);
        String commandKey = informResp.commands().getFirst().commandKey();

        // Ack Accepted -> Rebooting
        sessionService.handleCommandResponse("gw-00002", new DeviceCommandResponseRequest(commandKey, "ACCEPTED", null));

        // Edge Case: Device reboots and emits BOOT with target version, omitting TransferComplete
        DeviceInformRequest bootReq = new DeviceInformRequest("gw-00002", "2.0.0", "BOOT", null);
        sessionService.handleInform("gw-00002", bootReq);

        CampaignDevice updatedDev = deviceRepository.findByCommandKey(commandKey).orElseThrow();
        assertEquals(DeviceState.SUCCEEDED, updatedDev.getState(), "Should succeed via version match evidence");
    }

    @Test
    @DisplayName("Branch: Rollback detection on BOOT inform (old firmware reported)")
    void testRollbackDetectionOnBoot() {
        String campId = "camp-test-3";
        Campaign camp = new Campaign(campId, "2.0.0", "http://cdn/image.bin", "1.0.0", 10, 20.0);
        campaignRepository.save(camp);

        CampaignDevice dev = new CampaignDevice(campId, "gw-00003", "1.0.0");
        dev.setState(DeviceState.NOTIFIED);
        deviceRepository.save(dev);

        // Inform -> Download
        DeviceInformRequest informReq = new DeviceInformRequest("gw-00003", "1.0.0", "CONNECTION_REQUEST", null);
        DeviceInformResponse informResp = sessionService.handleInform("gw-00003", informReq);
        String commandKey = informResp.commands().getFirst().commandKey();

        // Ack Accepted -> Rebooting
        sessionService.handleCommandResponse("gw-00003", new DeviceCommandResponseRequest(commandKey, "ACCEPTED", null));

        // Edge Case: Device reboots but rolled back to old version 1.0.0
        DeviceInformRequest bootReq = new DeviceInformRequest("gw-00003", "1.0.0", "BOOT", null);
        sessionService.handleInform("gw-00003", bootReq);

        CampaignDevice updatedDev = deviceRepository.findByCommandKey(commandKey).orElseThrow();
        assertEquals(DeviceState.FAILED, updatedDev.getState(), "Should fail when rebooting on old version");
        assertTrue(updatedDev.getFailureReason().contains("old firmware"));
    }

    @Test
    @DisplayName("Branch (Negative): Device rejects download command with reason")
    void testCommandRejectedFailure() {
        String campId = "camp-test-4";
        Campaign camp = new Campaign(campId, "2.0.0", "http://cdn/image.bin", "1.0.0", 10, 20.0);
        campaignRepository.save(camp);

        CampaignDevice dev = new CampaignDevice(campId, "gw-00004", "1.0.0");
        dev.setState(DeviceState.NOTIFIED);
        deviceRepository.save(dev);

        DeviceInformRequest informReq = new DeviceInformRequest("gw-00004", "1.0.0", "CONNECTION_REQUEST", null);
        DeviceInformResponse informResp = sessionService.handleInform("gw-00004", informReq);
        String commandKey = informResp.commands().getFirst().commandKey();

        // Device rejects command
        DeviceCommandResponseRequest rejResp = new DeviceCommandResponseRequest(commandKey, "REJECTED", "StoragePartitionFull");
        sessionService.handleCommandResponse("gw-00004", rejResp);

        CampaignDevice updatedDev = deviceRepository.findByCommandKey(commandKey).orElseThrow();
        assertEquals(DeviceState.FAILED, updatedDev.getState());
        assertTrue(updatedDev.getFailureReason().contains("StoragePartitionFull"));
    }

    @Test
    @DisplayName("Branch (Negative): Flashing failure with non-zero faultCode (e.g. 9005 checksum mismatch)")
    void testTransferCompleteNonZeroFaultCode() {
        String campId = "camp-test-5";
        Campaign camp = new Campaign(campId, "2.0.0", "http://cdn/image.bin", "1.0.0", 10, 20.0);
        campaignRepository.save(camp);

        CampaignDevice dev = new CampaignDevice(campId, "gw-00005", "1.0.0");
        dev.setState(DeviceState.NOTIFIED);
        deviceRepository.save(dev);

        DeviceInformRequest informReq = new DeviceInformRequest("gw-00005", "1.0.0", "CONNECTION_REQUEST", null);
        DeviceInformResponse informResp = sessionService.handleInform("gw-00005", informReq);
        String commandKey = informResp.commands().getFirst().commandKey();

        sessionService.handleCommandResponse("gw-00005", new DeviceCommandResponseRequest(commandKey, "ACCEPTED", null));

        // TransferComplete with faultCode 9005 (Checksum error)
        TransferCompleteInfo tcInfo = new TransferCompleteInfo(commandKey, 9005, System.currentTimeMillis() - 5000, System.currentTimeMillis());
        DeviceInformRequest tcReq = new DeviceInformRequest("gw-00005", "1.0.0", "TRANSFER_COMPLETE", tcInfo);
        sessionService.handleInform("gw-00005", tcReq);

        CampaignDevice updatedDev = deviceRepository.findByCommandKey(commandKey).orElseThrow();
        assertEquals(DeviceState.FAILED, updatedDev.getState());
        assertTrue(updatedDev.getFailureReason().contains("9005"));
    }

    @Test
    @DisplayName("Branch (Idempotency): Duplicate TransferComplete and command responses are ignored")
    void testIdempotencyOfTerminalStateAndDuplicateResponses() {
        String campId = "camp-test-6";
        Campaign camp = new Campaign(campId, "2.0.0", "http://cdn/image.bin", "1.0.0", 10, 20.0);
        campaignRepository.save(camp);

        CampaignDevice dev = new CampaignDevice(campId, "gw-00006", "1.0.0");
        dev.setState(DeviceState.NOTIFIED);
        deviceRepository.save(dev);

        DeviceInformRequest informReq = new DeviceInformRequest("gw-00006", "1.0.0", "CONNECTION_REQUEST", null);
        DeviceInformResponse informResp = sessionService.handleInform("gw-00006", informReq);
        String commandKey = informResp.commands().getFirst().commandKey();

        // First ACCEPTED response
        sessionService.handleCommandResponse("gw-00006", new DeviceCommandResponseRequest(commandKey, "ACCEPTED", null));
        // Duplicate ACCEPTED response
        sessionService.handleCommandResponse("gw-00006", new DeviceCommandResponseRequest(commandKey, "ACCEPTED", null));

        // Device succeeds
        TransferCompleteInfo tcInfo = new TransferCompleteInfo(commandKey, 0, System.currentTimeMillis() - 5000, System.currentTimeMillis());
        sessionService.handleInform("gw-00006", new DeviceInformRequest("gw-00006", "2.0.0", "TRANSFER_COMPLETE", tcInfo));

        CampaignDevice updatedDev = deviceRepository.findByCommandKey(commandKey).orElseThrow();
        assertEquals(DeviceState.SUCCEEDED, updatedDev.getState());

        // Subsequent delayed duplicate TransferComplete or Inform packet MUST NOT alter SUCCEEDED state
        DeviceInformResponse dupResp = sessionService.handleInform("gw-00006", new DeviceInformRequest("gw-00006", "2.0.0", "TRANSFER_COMPLETE", tcInfo));
        assertTrue(dupResp.commands().isEmpty());

        updatedDev = deviceRepository.findByCommandKey(commandKey).orElseThrow();
        assertEquals(DeviceState.SUCCEEDED, updatedDev.getState());
    }

    @Test
    @DisplayName("Branch (Resilience): Safe Nudge Timeout Retry then TIMED_OUT fallback")
    void testNudgeTimeoutRetryAndFallback() {
        String campId = "camp-test-7";
        Campaign camp = new Campaign(campId, "2.0.0", "http://cdn/image.bin", "1.0.0", 10, 20.0);
        campaignRepository.save(camp);

        CampaignDevice dev = new CampaignDevice(campId, "gw-00007", "1.0.0");
        dev.setState(DeviceState.NOTIFIED);
        // Simulate elapsed time > 15s timeout
        dev.setLastStateChange(Instant.now().minus(20, ChronoUnit.SECONDS));
        dev.setNudgeRetries(0);
        deviceRepository.save(dev);

        // Execute cycle: Should retry (move back to PENDING and increment retry counter)
        campaignScheduler.executeCycle();

        CampaignDevice afterCycle1 = deviceRepository.findByCampaignIdAndDeviceId(campId, "gw-00007").orElseThrow();
        // It transitioned to PENDING, and scheduler immediately nudged it again (NOTIFIED) with retries=1
        assertEquals(1, afterCycle1.getNudgeRetries());

        // Age it again beyond timeout
        afterCycle1.setLastStateChange(Instant.now().minus(20, ChronoUnit.SECONDS));
        deviceRepository.save(afterCycle1);

        campaignScheduler.executeCycle();
        CampaignDevice afterCycle2 = deviceRepository.findByCampaignIdAndDeviceId(campId, "gw-00007").orElseThrow();
        assertEquals(2, afterCycle2.getNudgeRetries());

        // Age it again beyond timeout with max retries reached
        afterCycle2.setLastStateChange(Instant.now().minus(20, ChronoUnit.SECONDS));
        deviceRepository.save(afterCycle2);

        campaignScheduler.executeCycle();
        CampaignDevice afterFinal = deviceRepository.findByCampaignIdAndDeviceId(campId, "gw-00007").orElseThrow();
        assertEquals(DeviceState.TIMED_OUT, afterFinal.getState());
        assertTrue(afterFinal.getFailureReason().contains("retries"));
    }

    @Test
    @DisplayName("Branch (Resilience): Command ACK Timeout transitions to TIMED_OUT")
    void testDownloadAckTimeout() {
        String campId = "camp-test-8";
        Campaign camp = new Campaign(campId, "2.0.0", "http://cdn/image.bin", "1.0.0", 10, 20.0);
        campaignRepository.save(camp);

        CampaignDevice dev = new CampaignDevice(campId, "gw-00008", "1.0.0");
        dev.setState(DeviceState.DOWNLOAD_SENT);
        dev.setLastStateChange(Instant.now().minus(25, ChronoUnit.SECONDS)); // ACK timeout is 20s
        deviceRepository.save(dev);

        campaignScheduler.executeCycle();

        CampaignDevice timedOutDev = deviceRepository.findByCampaignIdAndDeviceId(campId, "gw-00008").orElseThrow();
        assertEquals(DeviceState.TIMED_OUT, timedOutDev.getState());
        assertTrue(timedOutDev.getFailureReason().contains("ACK timeout"));
    }

    @Test
    @DisplayName("Branch (Resilience): Reboot SLA Timeout transitions to TIMED_OUT")
    void testRebootSlaTimeout() {
        String campId = "camp-test-9";
        Campaign camp = new Campaign(campId, "2.0.0", "http://cdn/image.bin", "1.0.0", 10, 20.0);
        campaignRepository.save(camp);

        CampaignDevice dev = new CampaignDevice(campId, "gw-00009", "1.0.0");
        dev.setState(DeviceState.REBOOTING);
        dev.setLastStateChange(Instant.now().minus(65, ChronoUnit.SECONDS)); // Reboot SLA is 60s
        deviceRepository.save(dev);

        campaignScheduler.executeCycle();

        CampaignDevice timedOutDev = deviceRepository.findByCampaignIdAndDeviceId(campId, "gw-00009").orElseThrow();
        assertEquals(DeviceState.TIMED_OUT, timedOutDev.getState());
        assertTrue(timedOutDev.getFailureReason().contains("Reboot timeout"));
    }

    @Test
    @DisplayName("Branch (Safety): Circuit Breaker Auto-Halts campaign when failure threshold exceeded")
    void testCircuitBreakerThresholdAutoHalt() {
        String campId = "camp-test-10";
        // Failure threshold: 25.0%
        Campaign camp = new Campaign(campId, "2.0.0", "http://cdn/image.bin", "1.0.0", 10, 25.0);
        campaignRepository.save(camp);

        // Populate sample size of 20 devices (10 succeeded, 10 failed = 50% failure rate > 25% threshold)
        for (int i = 1; i <= 10; i++) {
            CampaignDevice succ = new CampaignDevice(campId, "gw-succ-" + i, "1.0.0");
            succ.setState(DeviceState.SUCCEEDED);
            deviceRepository.save(succ);

            CampaignDevice fail = new CampaignDevice(campId, "gw-fail-" + i, "1.0.0");
            fail.setState(DeviceState.FAILED);
            deviceRepository.save(fail);
        }

        // Add 1 pending device
        CampaignDevice pending = new CampaignDevice(campId, "gw-pend-1", "1.0.0");
        pending.setState(DeviceState.PENDING);
        deviceRepository.save(pending);

        campaignScheduler.executeCycle();

        Campaign updatedCamp = campaignRepository.findById(campId).orElseThrow();
        assertEquals(CampaignStatus.HALTED, updatedCamp.getStatus());
        assertNotNull(updatedCamp.getHaltReason());
        assertTrue(updatedCamp.getHaltReason().contains("Failure rate 50.00% exceeded threshold 25.00%"));

        // Verify pending device was NOT nudged because circuit breaker halted campaign
        CampaignDevice pendingCheck = deviceRepository.findByCampaignIdAndDeviceId(campId, "gw-pend-1").orElseThrow();
        assertEquals(DeviceState.PENDING, pendingCheck.getState(), "Should remain PENDING and not dispatched");
    }

    @Test
    @DisplayName("Negative Scenario: Unknown commandKey in command-response does not throw exception")
    void testUnknownCommandKeyHandling() {
        assertDoesNotThrow(() -> {
            sessionService.handleCommandResponse("gw-unknown", new DeviceCommandResponseRequest("cmd-non-existent", "ACCEPTED", null));
        });
    }

    @Test
    @DisplayName("Negative Scenario: Inform from device not in flight returns empty commands without mutation")
    void testInformFromUnregisteredDeviceReturnsEmptyCommands() {
        DeviceInformRequest req = new DeviceInformRequest("gw-ghost", "1.0.0", "CONNECTION_REQUEST", null);
        DeviceInformResponse resp = sessionService.handleInform("gw-ghost", req);
        assertNotNull(resp);
        assertTrue(resp.commands().isEmpty());
    }

    @Test
    @DisplayName("Observability: Get devices associated with a campaign with state filtering and pagination")
    void testGetCampaignDevices() {
        String campId = "camp-devices-test";
        Campaign camp = new Campaign(campId, "2.0.0", "http://cdn/image.bin", "1.0.0", 10, 20.0);
        campaignRepository.save(camp);

        CampaignDevice d1 = new CampaignDevice(campId, "gw-obs-1", "1.0.0");
        d1.setState(DeviceState.SUCCEEDED);
        Instant now = Instant.now();
        d1.setNotifiedAt(now.minusSeconds(30));
        d1.setDownloadSentAt(now.minusSeconds(28));
        d1.setAcceptedAt(now.minusSeconds(24));
        d1.setRebootingAt(now.minusSeconds(24));
        d1.setTerminalAt(now);

        CampaignDevice d2 = new CampaignDevice(campId, "gw-obs-2", "1.0.0");
        d2.setState(DeviceState.FAILED);
        d2.setFailureReason("TransferComplete faultCode: 9010");
        CampaignDevice d3 = new CampaignDevice(campId, "gw-obs-3", "1.0.0");
        d3.setState(DeviceState.TIMED_OUT);
        d3.setFailureReason("Reboot timeout exceeded");

        deviceRepository.saveAll(java.util.List.of(d1, d2, d3));

        // 1. Get all devices (page 0, size 10)
        Optional<CampaignDevicesPageResponse> allResp = campaignService.getCampaignDevices(campId, null, PageRequest.of(0, 10));
        assertTrue(allResp.isPresent());
        assertEquals(3, allResp.get().totalElements());
        assertEquals(3, allResp.get().devices().size());

        CampaignDeviceResponse dev1 = allResp.get().devices().stream()
                .filter(d -> d.deviceId().equals("gw-obs-1"))
                .findFirst().orElseThrow();
        assertNotNull(dev1.durations());
        assertEquals(2L, dev1.durations().nudgeDurationSeconds());
        assertEquals(4L, dev1.durations().downloadDurationSeconds());
        assertEquals(24L, dev1.durations().rebootDurationSeconds());
        assertEquals(30L, dev1.durations().totalDurationSeconds());

        // 2. Filter by FAILED state
        Optional<CampaignDevicesPageResponse> failedResp = campaignService.getCampaignDevices(campId, DeviceState.FAILED, PageRequest.of(0, 10));
        assertTrue(failedResp.isPresent());
        assertEquals(1, failedResp.get().totalElements());
        assertEquals("gw-obs-2", failedResp.get().devices().get(0).deviceId());
        assertEquals("TransferComplete faultCode: 9010", failedResp.get().devices().get(0).failureReason());

        // 3. Negative: Non-existent campaign
        Optional<CampaignDevicesPageResponse> missing = campaignService.getCampaignDevices("non-existent-id", null, PageRequest.of(0, 10));
        assertTrue(missing.isEmpty());
    }

    @Test
    @DisplayName("Requirement 8: Controller state survives controller restart mid-campaign")
    void testControllerStateSurvivesRestartMidCampaign() {
        String campId = "camp-restart-test";
        Campaign camp = new Campaign(campId, "2.0.0", "http://cdn/image.bin", "1.0.0", 10, 20.0);
        camp.setStatus(CampaignStatus.RUNNING);
        campaignRepository.save(camp);

        // Simulated mid-campaign fleet state
        CampaignDevice inFlight = new CampaignDevice(campId, "gw-restart-inflight", "1.0.0");
        inFlight.setState(DeviceState.REBOOTING);
        inFlight.setRebootingAt(Instant.now());

        CampaignDevice pending = new CampaignDevice(campId, "gw-restart-pending", "1.0.0");
        pending.setState(DeviceState.PENDING);

        CampaignDevice completed = new CampaignDevice(campId, "gw-restart-done", "1.0.0");
        completed.setState(DeviceState.SUCCEEDED);

        deviceRepository.saveAll(java.util.List.of(inFlight, pending, completed));

        // Simulate application restart: scheduler runs its polling cycle
        campaignScheduler.executeCycle();

        // 1. Campaign remains RUNNING
        Campaign reloadedCamp = campaignRepository.findById(campId).orElseThrow();
        assertEquals(CampaignStatus.RUNNING, reloadedCamp.getStatus());

        // 2. Pending device was picked up and dispatched to NOTIFIED
        CampaignDevice reloadedPending = deviceRepository.findByCampaignIdAndDeviceId(campId, "gw-restart-pending").orElseThrow();
        assertEquals(DeviceState.NOTIFIED, reloadedPending.getState());

        // 3. In-flight rebooting device remains intact
        CampaignDevice reloadedInFlight = deviceRepository.findByCampaignIdAndDeviceId(campId, "gw-restart-inflight").orElseThrow();
        assertEquals(DeviceState.REBOOTING, reloadedInFlight.getState());

        // 4. Completed device remains SUCCEEDED
        CampaignDevice reloadedDone = deviceRepository.findByCampaignIdAndDeviceId(campId, "gw-restart-done").orElseThrow();
        assertEquals(DeviceState.SUCCEEDED, reloadedDone.getState());
    }
}
