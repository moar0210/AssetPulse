package io.github.moar0210.assetpulse.assets;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssetQueryService {

    private final AssetQueryRepository assetRepository;

    public AssetQueryService(AssetQueryRepository assetRepository) {
        this.assetRepository = assetRepository;
    }

    @Transactional(readOnly = true)
    public AssetListResponse listForOrganisation(UUID organisationId) {
        return new AssetListResponse(assetRepository.findByOrganisationId(organisationId));
    }

    @Transactional(readOnly = true)
    public AssetDetailResponse detailForOrganisation(UUID organisationId, UUID assetId) {
        AssetQueryRepository.AssetRow asset =
                assetRepository
                        .findOneByOrganisationIdAndId(organisationId, assetId)
                        .orElseThrow(AssetNotFoundException::new);
        return new AssetDetailResponse(
                asset.id(),
                asset.assetCode(),
                asset.name(),
                assetRepository.findSensorsByOrganisationIdAndAssetId(organisationId, assetId));
    }
}
