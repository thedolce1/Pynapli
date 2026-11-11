# Third-Party Data

Datasets bundled into the product, with their licenses and attribution obligations.

## GeoNames place directory (travel-mode search)

- **What:** `server/src/main/resources/travel/travel_places.tsv.gz` — world cities (GeoNames
  `cities15000` + `admin1CodesASCII` region names) and US ZIP / Canadian forward-sortation-area
  postal centroids (GeoNames postal exports). Backs `GET /v1/location/travel/search`.
- **License:** [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Attribution required.
- **Attribution:** "Contains data from [GeoNames](https://www.geonames.org), licensed under
  CC BY 4.0."
- **Regeneration:** `scripts/generate-travel-places.sh <workdir>` (header comment documents the
  source download URLs). The TSV header carries a version stamp the server's startup loader uses
  to decide whether to reload `travel_places`.
- **In the app:** the attribution line is shown under Travel mode search results (where the data
  is displayed) and as "Place data: GeoNames (CC BY 4.0)" in Settings > Legal, linking to
  geonames.org (`LegalLinks.GEONAMES_ATTRIBUTION` / `LegalLinks.GEONAMES`).
