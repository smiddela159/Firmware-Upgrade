package com.att.device.management.repository;

import com.att.device.management.domain.Campaign;
import com.att.device.management.domain.CampaignStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CampaignRepository extends JpaRepository<Campaign, String> {
    List<Campaign> findByStatus(CampaignStatus status);
}

