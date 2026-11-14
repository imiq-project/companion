# Third-party notices and public data sources

Companion includes public data and third-party assets. Attribution and source
terms apply independently to each component. This document is not a blanket
license for the application, branding or all bundled artwork.

## OpenStreetMap and Magdeburg reference data

Copyright © OpenStreetMap contributors. OpenStreetMap data is available under
the [Open Database License 1.0](https://opendatacommons.org/licenses/odbl/1-0/).
See [OpenStreetMap copyright and attribution](https://www.openstreetmap.org/copyright).

`contracts/magdeburg-mobility.json` contains mapped parking locations, parking
entrances and public transport stops/platforms for Magdeburg. It does not describe
live parking occupancy or guarantee public access. It was obtained through the
[Overpass API](https://overpass-api.de/api/interpreter). The query, retrieval time
and OSM base timestamp are retained in the data file.

`contracts/magdeburg-tram-lines.json` contains OSM route relations and tram track
ways, with query and timestamps retained in the file. These are mapped reference
alignments, not measured vehicle trajectories or certified GTFS service shapes.
Visible OSM attribution is retained in the Android map.

Initial transport setup downloads a Magdeburg PBF extract from
[BBBike](https://download.bbbike.org/osm/bbbike/Magdeburg/). The extract remains
OpenStreetMap data subject to ODbL; it is generated runtime input, not committed
to this repository.

## Map presentation and icons

Android map style and icon notices are shipped alongside the assets:

- `frontend/app/src/main/assets/maps/LICENSE.openfreemap.md`
- `frontend/app/src/main/assets/maps/LICENSE.liberty.md`
- `frontend/app/src/main/assets/licenses/LICENSE.material-icons.txt`

Preserve those files when redistributing the associated resources, and preserve
the map's interactive attribution to OpenFreeMap, OpenMapTiles and OpenStreetMap.
Fonts and their accompanying license files must also retain their original terms.

## Barlow typeface

The Android interface bundles Barlow Regular, SemiBold, Bold and Italic,
the IMIQ project's house typeface. These fonts are distributed under the
SIL Open Font License 1.1. Copyright and complete license text are retained in
`frontend/app/src/main/assets/licenses/LICENSE.barlow.txt`.
Sources: [Barlow in the Google Fonts repository](https://github.com/google/fonts/tree/main/ofl/barlow).
The bundled fonts are available offline; the app does not download fonts at runtime.

## Climate factors

`contracts/climate-factors-v1.json` preserves original UBA row identifiers, values,
source and calculation boundary. Source: the German Environment Agency
([UBA emissions data](https://www.umweltbundesamt.de/en/node/120142)), CC0 1.0.
Fleet averages are not measurements of an individual vehicle or journey.

## Vehicle catalogue

`contracts/vehicle-catalogue-v1.json.gz` uses ADEME Car Labelling, published by
[ADEME, France](https://data.ademe.fr/datasets/ademe-car-labelling), under the
[Licence Ouverte / Open Licence](https://www.etalab.gouv.fr/licence-ouverte-open-licence/).
Catalogue snapshot updated 2026-10-14 and retrieved 2026-11-04. Consumption
references may use a range midpoint or a single published bound. Calculations
are project adaptations and do not imply endorsement by ADEME.

`contracts/vehicle-names-gb-v1.json.gz` uses Department for Transport / Driver and
Vehicle Licensing Agency VEH0160 GB data, from
[vehicle licensing statistics data files](https://www.gov.uk/government/statistical-data-sets/vehicle-licensing-statistics-data-files).
Contains public sector information licensed under the
[Open Government Licence v3.0](https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/).
Vehicle names do not include model-specific fuel consumption.

## Transit inputs

The transport tools obtain static schedules from the
[GTFS.DE public German feed](https://download.gtfs.de/germany/nv_free/latest.zip)
and realtime information from [GTFS.DE](https://realtime.gtfs.de/realtime-free.pb).
Feed attribution records are retained by the MVB extraction when supplied by
the source. Consult the publisher's applicable terms before redistributing feeds.
The source repository does not bundle downloaded feeds or built graphs.

## OpenTripPlanner and dependencies

The bootstrap command downloads the unmodified OTP 2.7.0 JAR from the
[OpenTripPlanner upstream release](https://github.com/opentripplanner/OpenTripPlanner/releases/tag/v2.7.0).
OpenTripPlanner is a separate upstream project; see its
[license](https://github.com/opentripplanner/OpenTripPlanner/blob/v2.7.0/LICENSE).
The JAR is not committed to this repository.

Python, Android, Gradle and other dependencies retain their respective upstream
licenses. Their dependency declarations do not transfer ownership or imply
endorsement. Project-specific companion artwork and branding are not assigned
an open license by this notice.
