#!/usr/bin/env bash
# Regenerates server/src/main/resources/travel/travel_places.tsv.gz from GeoNames exports.
#
# Source data (c) GeoNames (https://www.geonames.org), licensed CC BY 4.0
# (https://creativecommons.org/licenses/by/4.0/). See docs/THIRD_PARTY_DATA.md.
#
# Usage:
#   ./scripts/generate-travel-places.sh <workdir>
# where <workdir> contains: cities15000.txt, admin1.txt (admin1CodesASCII.txt),
# us/US.txt, ca/CA.txt — downloaded from:
#   https://download.geonames.org/export/dump/cities15000.zip
#   https://download.geonames.org/export/dump/admin1CodesASCII.txt
#   https://download.geonames.org/export/zip/US.zip
#   https://download.geonames.org/export/zip/CA.zip
#
# Output TSV columns: kind, country_code, postal_code, name, ascii_name, region,
# latitude, longitude, population. First line is a #version header the startup
# loader compares against the loaded dataset to decide whether to reload.
set -euo pipefail

workdir="${1:?usage: generate-travel-places.sh <workdir>}"
out_dir="$(dirname "$0")/../server/src/main/resources/travel"
mkdir -p "$out_dir"
out="$out_dir/travel_places.tsv"
version="geonames-$(date +%Y%m%d)"

echo "#pynapli-travel-places version=$version source=GeoNames(CC-BY-4.0)" > "$out"

# Cities: name + ascii name + admin1 region resolved from admin1CodesASCII.
awk -F'\t' -v OFS='\t' '
  NR == FNR { admin[$1] = $3; next }
  $5 != "" && $6 != "" {
    region = admin[$9 "." $11]
    print "city", $9, "", $2, $3, region, $5, $6, $15
  }
' "$workdir/admin1.txt" "$workdir/cities15000.txt" >> "$out"

# US ZIP + CA forward-sortation-area postal rows. Region keeps the short
# admin1 code (NV, ON) — reads better next to a postal code than a full name.
for f in "$workdir/us/US.txt" "$workdir/ca/CA.txt"; do
  awk -F'\t' -v OFS='\t' '
    $10 != "" && $11 != "" {
      print "postal", $1, $2, $3, $3, $5, $10, $11, 0
    }
  ' "$f" >> "$out"
done

gzip -nf9 "$out"
echo "Wrote $out.gz ($(wc -c < "$out.gz") bytes, $(gzip -dc "$out.gz" | wc -l) lines, version $version)"
