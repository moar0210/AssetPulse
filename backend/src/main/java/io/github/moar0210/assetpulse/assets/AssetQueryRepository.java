package io.github.moar0210.assetpulse.assets;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AssetQueryRepository {

    private static final String FIND_BY_ORGANISATION =
            """
            SELECT id, asset_code, name
            FROM asset
            WHERE organisation_id = :organisationId
            ORDER BY name, id
            LIMIT 100
            """;

    private final JdbcClient jdbcClient;

    public AssetQueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<AssetSummaryResponse> findByOrganisationId(UUID organisationId) {
        return jdbcClient
                .sql(FIND_BY_ORGANISATION)
                .param("organisationId", organisationId)
                .query(
                        (resultSet, rowNumber) ->
                                new AssetSummaryResponse(
                                        resultSet.getObject("id", UUID.class),
                                        resultSet.getString("asset_code"),
                                        resultSet.getString("name")))
                .list();
    }
}
