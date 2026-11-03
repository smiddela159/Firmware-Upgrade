package com.att.device.management.repository;

import com.att.device.management.domain.CampaignStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CampaignStatusHistoryRepository extends JpaRepository<CampaignStatusHistory, Long> {
    List<CampaignStatusHistory> findByCampaignIdOrderByChangedAtAsc(String campaignId);
}

