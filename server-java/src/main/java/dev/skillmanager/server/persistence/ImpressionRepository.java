package dev.skillmanager.server.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ImpressionRepository extends JpaRepository<ImpressionRow, Long> {
    long countByCampaignId(String campaignId);
}
