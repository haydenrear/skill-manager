package dev.skillmanager.server.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ConversionRepository extends JpaRepository<ConversionRow, Long> {
    long countByCampaignId(String campaignId);
}
