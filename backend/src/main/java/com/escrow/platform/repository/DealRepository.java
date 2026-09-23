package com.escrow.platform.repository;

import com.escrow.platform.model.DealRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DealRepository extends JpaRepository<DealRecord, Long> {

    List<DealRecord> findByClientAddressIgnoreCaseOrFreelancerAddressIgnoreCase(String clientAddress, String freelancerAddress);
}
