package ai.godance.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import ai.godance.domain.ModelChangeProposal;
import ai.godance.domain.ProposalStatus;

public interface ModelChangeProposalRepository extends JpaRepository<ModelChangeProposal, Long> {

    List<ModelChangeProposal> findByStatusOrderByDetectedAtDesc(ProposalStatus status);

    List<ModelChangeProposal> findAllByOrderByDetectedAtDesc();
}
