package com.garganttua.api.core.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.core.filter.Filter;

/**
 * A map search seen as tiles: the latitude and longitude bounds of a query, the rest of it, and the cells of a
 * fixed grid that cover the bounds.
 *
 * <p>
 * Only a query that bounds both fields on both sides, with numbers, is one — any other is read as usual. The grid
 * is the same for every query: cells of {@code 180 / 2^z} degrees, {@code z} chosen so that the bounds span at most
 * three cells per axis. Two neighbouring viewports therefore share most of their cells, whatever their exact
 * corners.
 * </p>
 *
 * @param latitude  the latitude bounds asked
 * @param longitude the longitude bounds asked
 * @param rest      every other condition of the query, the access filter included; empty when there is none
 */
record TileQuery(Bounds latitude, Bounds longitude, Optional<IFilter> rest) {

    private static final int MAX_LEVEL = 24;
    private static final double WORLD = 180d;

    /** Bounds on one field, each side inclusive or not, as the query wrote them. */
    record Bounds(double low, boolean lowInclusive, double high, boolean highInclusive) {

        boolean contains(double value) {
            return (this.lowInclusive ? value >= this.low : value > this.low)
                    && (this.highInclusive ? value <= this.high : value < this.high);
        }

        double span() {
            return this.high - this.low;
        }
    }

    /** One cell of the grid, and the query that reads it. */
    record Tile(int level, long row, long column, double south, double west, double size) {

        String id() {
            return this.level + "/" + this.row + "/" + this.column;
        }
    }

    /**
     * Reads {@code filter} as a map search on {@code latitude} and {@code longitude}.
     *
     * @return the tile query, empty when the filter does not bound both fields on both sides with numbers
     */
    static Optional<TileQuery> parse(IFilter filter, String latitude, String longitude) {
        if (filter == null) {
            return Optional.empty();
        }
        List<IFilter> conjuncts = new ArrayList<>();
        flatten(filter, conjuncts);
        BoundsBuilder lat = new BoundsBuilder();
        BoundsBuilder lon = new BoundsBuilder();
        List<Filter> rest = new ArrayList<>();
        for (IFilter conjunct : conjuncts) {
            String field = fieldOf(conjunct);
            BoundsBuilder target = latitude.equals(field) ? lat : longitude.equals(field) ? lon : null;
            if (target != null && target.accept(conjunct.getFilters().get(0))) {
                continue;
            }
            if (!(conjunct instanceof Filter concrete)) {
                return Optional.empty();
            }
            rest.add(concrete);
        }
        if (!lat.complete() || !lon.complete()) {
            return Optional.empty();
        }
        Optional<IFilter> others = rest.isEmpty() ? Optional.empty()
                : Optional.of(rest.size() == 1 ? rest.get(0) : Filter.and(rest.toArray(new Filter[0])));
        return Optional.of(new TileQuery(lat.build(), lon.build(), others));
    }

    /** {@return the cells covering the bounds — at most three per axis} */
    List<Tile> cover() {
        double span = Math.max(this.latitude.span(), this.longitude.span());
        int level = 0;
        double size = WORLD;
        while (level < MAX_LEVEL && size / 2 >= span / 2) {
            size /= 2;
            level++;
        }
        long firstRow = (long) Math.floor((this.latitude.low() + 90) / size);
        long lastRow = (long) Math.floor((this.latitude.high() + 90) / size);
        long firstColumn = (long) Math.floor((this.longitude.low() + 180) / size);
        long lastColumn = (long) Math.floor((this.longitude.high() + 180) / size);
        List<Tile> tiles = new ArrayList<>();
        for (long row = firstRow; row <= lastRow; row++) {
            for (long column = firstColumn; column <= lastColumn; column++) {
                tiles.add(new Tile(level, row, column, row * size - 90, column * size - 180, size));
            }
        }
        return tiles;
    }

    /** {@return the query that reads one cell: the rest of the search, within the cell's bounds} */
    Filter read(Tile tile, String latitudeField, String longitudeField) {
        List<Filter> conditions = new ArrayList<>();
        this.rest.ifPresent(other -> conditions.add((Filter) other));
        conditions.add(Filter.gte(latitudeField, tile.south()));
        conditions.add(Filter.lte(latitudeField, tile.south() + tile.size()));
        conditions.add(Filter.gte(longitudeField, tile.west()));
        conditions.add(Filter.lte(longitudeField, tile.west() + tile.size()));
        return Filter.and(conditions.toArray(new Filter[0]));
    }

    /** Whether a row at ({@code lat}, {@code lon}) belongs to the search asked — its exact bounds. */
    boolean contains(double lat, double lon) {
        return this.latitude.contains(lat) && this.longitude.contains(lon);
    }

    private static void flatten(IFilter filter, List<IFilter> into) {
        if (Filter.OPERATOR_AND.equals(filter.getName()) && filter.getFilters() != null) {
            for (IFilter child : filter.getFilters()) {
                flatten(child, into);
            }
        } else {
            into.add(filter);
        }
    }

    private static String fieldOf(IFilter conjunct) {
        if (!Filter.OPERATOR_FIELD.equals(conjunct.getName()) || conjunct.getFilters() == null
                || conjunct.getFilters().size() != 1) {
            return null;
        }
        return String.valueOf(conjunct.getValue());
    }

    /** Collects the bounds of one field, keeping the tightest on each side. */
    private static final class BoundsBuilder {
        private Double low;
        private boolean lowInclusive;
        private Double high;
        private boolean highInclusive;

        /** Takes {@code operator} as a bound; false when it is not one — then it stays in the rest. */
        boolean accept(IFilter operator) {
            if (!(operator.getValue() instanceof Number number)) {
                return false;
            }
            double value = number.doubleValue();
            switch (operator.getName()) {
                case Filter.OPERATOR_GREATER_THAN -> lower(value, false);
                case Filter.OPERATOR_GREATER_THAN_EXCLUSIVE -> lower(value, true);
                case Filter.OPERATOR_LOWER_THAN -> upper(value, false);
                case Filter.OPERATOR_LOWER_THAN_EXCLUSIVE -> upper(value, true);
                default -> {
                    return false;
                }
            }
            return true;
        }

        private void lower(double value, boolean inclusive) {
            if (this.low == null || value > this.low || Double.compare(value, this.low) == 0 && !inclusive) {
                this.low = value;
                this.lowInclusive = inclusive;
            }
        }

        private void upper(double value, boolean inclusive) {
            if (this.high == null || value < this.high || Double.compare(value, this.high) == 0 && !inclusive) {
                this.high = value;
                this.highInclusive = inclusive;
            }
        }

        boolean complete() {
            return this.low != null && this.high != null;
        }

        Bounds build() {
            return new Bounds(this.low, this.lowInclusive, this.high, this.highInclusive);
        }
    }
}
