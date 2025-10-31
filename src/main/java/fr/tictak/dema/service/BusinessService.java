package fr.tictak.dema.service;

import fr.tictak.dema.dto.in.BusinessDTO;
import fr.tictak.dema.model.Business;
import fr.tictak.dema.repository.BusinessRepository;
import org.springframework.stereotype.Service;

@Service
public class BusinessService {



    private final BusinessRepository businessRepository;

    public BusinessService(BusinessRepository businessRepository) {
        this.businessRepository = businessRepository;
    }

    public Business createBusiness(BusinessDTO dto) {
        Business business = new Business();
        business.setName(dto.name());
        business.setEmail(dto.email());
        business.setAddress(dto.address());
        business.setPhone(dto.phone());
        business.setWebsite(dto.website());

        // Set document URLs from DTO
        business.setAttestationCapaciteUrl(dto.attestationCapaciteUrl());
        business.setKbisUrl(dto.kbisUrl());
        business.setAssuranceTransportUrl(dto.assuranceTransportUrl());
        business.setIdentityProofUrl(dto.identityProofUrl());
        business.setAttestationVigilanceUrl(dto.attestationVigilanceUrl());
        business.setAttestationRegulariteFiscaleUrl(dto.attestationRegulariteFiscaleUrl());

        return businessRepository.save(business);
    }
}