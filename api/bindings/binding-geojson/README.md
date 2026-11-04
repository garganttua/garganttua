# Garganttua API GeoJSON Binding

## Description

`garganttua-api-binding-geojson` pins the `org.geojson` geometry model (`geojson-jackson`) and ships
`GeoJsonCodec`, which turns a geometry into GeoJSON and back **without any JSON library**. The DAOs store
geometries through it: the MongoDB DAO as the sub-document a `2dsphere` index reads, the PostgreSQL DAO as the
text `ST_GeomFromGeoJSON` reads and `ST_AsGeoJSON` writes. Both stores therefore write a geometry the same way,
and neither needs Jackson — nor reflection metadata in a native image — to do it.

Of Jackson, only `jackson-annotations` remains: the `org.geojson` classes carry its annotations, which the HTTP
serializer (`garganttua-api-binding-jackson`) uses to read and write geometries in request and response bodies.

**Key Features:**
- **The seven geometries of RFC 7946** — `Point`, `MultiPoint`, `LineString`, `MultiLineString`, `Polygon`,
  `MultiPolygon`, `GeometryCollection`; a position keeps its altitude and further elements
- **Refusals with a reason** — a `Feature` or `FeatureCollection` is not a geometry (its `properties` are
  arbitrary values), a non-finite coordinate is not a coordinate; `crs` and `bbox` are not carried
- **No reflection** — the codec reads and builds the model directly: nothing to register for native-image

## Installation

<!-- AUTO-GENERATED-START -->
### Installation with Maven
```xml
<dependency>
    <groupId>com.garganttua</groupId>
    <artifactId>garganttua-api-binding-geojson</artifactId>
    <version>3.0.0-ALPHA32</version>
</dependency>
```

### Actual version
3.0.0-ALPHA32

### Dependencies
 - `de.grundid.opendatalab:geojson-jackson:${geojson-jackson.version}`
 - `com.fasterxml.jackson.core:jackson-annotations:${jackson.version}`
 - `org.junit.jupiter:junit-jupiter-engine:test`

<!-- AUTO-GENERATED-END -->

## Usage

```java
Map<String, Object> geoJson = GeoJsonCodec.toMap(new Point(2.3522, 48.8566));
// {type=Point, coordinates=[2.3522, 48.8566]}
GeoJsonObject geometry = GeoJsonCodec.fromJson("{\"type\":\"Point\",\"coordinates\":[2.3522,48.8566]}");
```

## License

This module is distributed under the MIT License.
