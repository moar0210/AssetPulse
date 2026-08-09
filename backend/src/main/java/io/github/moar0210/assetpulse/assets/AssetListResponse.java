package io.github.moar0210.assetpulse.assets;

import java.util.List;

public record AssetListResponse(List<AssetSummaryResponse> assets) {

    public AssetListResponse {
        assets = List.copyOf(assets);
    }
}
