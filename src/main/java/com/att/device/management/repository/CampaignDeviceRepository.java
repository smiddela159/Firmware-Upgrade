package com.att.device.management.repository;

import com.att.device.management.domain.CampaignDevice;
import com.att.device.management.domain.DeviceState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CampaignDeviceRepository extends JpaRepository<CampaignDevice, Long> {

    List<CampaignDevice> findByCampaignId(String campaignId);

    Page<CampaignDevice> findAllByCampaignId(String campaignId, Pageable pageable);

    Page<CampaignDevice> findAllByCampaignIdAndState(String campaignId, DeviceState state, Pageable pageable);

    long countByCampaignId(String campaignId);

    Optional<CampaignDevice> findByCampaignIdAndDeviceId(String campaignId, String deviceId);

    List<CampaignDevice> findByDeviceIdAndStateIn(String deviceId, Collection<DeviceState> states);

    Optional<CampaignDevice> findByCommandKey(String commandKey);

    long countByCampaignIdAndState(String campaignId, DeviceState state);

    long countByCampaignIdAndStateIn(String campaignId, Collection<DeviceState> states);

    List<CampaignDevice> findByCampaignIdAndState(String campaignId, DeviceState state, Pageable pageable);

    @Query("SELECT cd FROM CampaignDevice cd WHERE cd.campaignId = :campaignId AND cd.state IN :states")
    List<CampaignDevice> findInFlightDevices(@Param("campaignId") String campaignId, @Param("states") Collection<DeviceState> states);
}

