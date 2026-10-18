package sc.aco;

import mindustry.type.Sector;

public final class SectorKeyResolver {
    private SectorKeyResolver() {
    }

    public static String keyOf(Sector sector) {
        if (sector == null || sector.planet == null) {
            throw new IllegalArgumentException(
                "Sector and planet must be present"
            );
        }

        // Preserve preset keys used by campaign progression, e.g. groundZero.
        if (sector.preset != null
            && sector.preset.name != null
            && !sector.preset.name.isBlank()) {
            return sector.preset.name;
        }

        // Non-preset sectors need a globally meaningful identity.
        return sector.planet.name + ":" + sector.id;
    }
}
