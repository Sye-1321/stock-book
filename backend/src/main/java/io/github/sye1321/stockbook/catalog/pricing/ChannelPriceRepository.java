package io.github.sye1321.stockbook.catalog.pricing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ChannelPriceRepository {
    private static final String SELECT = """
            select price.id, price.product_id, price.unit_id, unit.code as unit_code,
                   price.channel, price.amount, price.conversion_factor,
                   price.valid_from, price.valid_to, price.recorded_by
            from product_channel_price price
            join unit_of_measure unit on unit.id = price.unit_id
            """;
    private final JdbcClient jdbcClient;

    public ChannelPriceRepository(JdbcClient jdbcClient) { this.jdbcClient = jdbcClient; }

    public List<ChannelPrice> findHistory(UUID productId) {
        return jdbcClient.sql(SELECT + " where price.product_id = :productId"
                        + " order by price.valid_from desc, price.channel, unit.code")
                .param("productId", productId)
                .query(ChannelPriceRepository::map)
                .list();
    }

    public Optional<ChannelPrice> findCurrentForUpdate(UUID productId, UUID unitId, SalesChannel channel) {
        return jdbcClient.sql(SELECT + " where price.product_id = :productId and price.unit_id = :unitId"
                        + " and price.channel = :channel and price.valid_to is null for update of price")
                .param("productId", productId)
                .param("unitId", unitId)
                .param("channel", channel.name())
                .query(ChannelPriceRepository::map)
                .optional();
    }

    public Optional<ChannelPrice> findCurrent(UUID productId, UUID unitId, SalesChannel channel) {
        return jdbcClient.sql(SELECT + " where price.product_id = :productId and price.unit_id = :unitId"
                        + " and price.channel = :channel and price.valid_to is null")
                .param("productId", productId)
                .param("unitId", unitId)
                .param("channel", channel.name())
                .query(ChannelPriceRepository::map)
                .optional();
    }

    public void lockProduct(UUID productId) {
        jdbcClient.sql("select id from product where id = :id for update")
                .param("id", productId).query(UUID.class).single();
    }

    public void insert(ChannelPrice price, Instant expectedCurrentValidFrom) {
        jdbcClient.sql("""
                select catalog_supersede_channel_price(
                    :id, :productId, :unitId, :channel, :amount, :conversionFactor,
                    :validFrom, :expectedCurrentValidFrom, :recordedBy
                )
                """)
                .param("id", price.id()).param("productId", price.productId())
                .param("unitId", price.unitId()).param("channel", price.channel().name())
                .param("amount", price.amount()).param("conversionFactor", price.conversionFactor())
                .param("validFrom", timestamp(price.validFrom()))
                .param("expectedCurrentValidFrom", expectedCurrentValidFrom == null
                        ? null : timestamp(expectedCurrentValidFrom))
                .param("recordedBy", price.recordedBy()).query(UUID.class).single();
    }

    private static ChannelPrice map(ResultSet rs, int rowNumber) throws SQLException {
        OffsetDateTime validTo = rs.getObject("valid_to", OffsetDateTime.class);
        return new ChannelPrice(
                rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class),
                rs.getObject("unit_id", UUID.class), rs.getString("unit_code"),
                SalesChannel.valueOf(rs.getString("channel")), rs.getBigDecimal("amount"),
                rs.getBigDecimal("conversion_factor"),
                rs.getObject("valid_from", OffsetDateTime.class).toInstant(),
                validTo == null ? null : validTo.toInstant(), rs.getObject("recorded_by", UUID.class));
    }

    private static OffsetDateTime timestamp(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
