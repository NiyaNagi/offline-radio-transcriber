package org.ort.data.dao

import androidx.room.Dao
import androidx.room.Query
import org.ort.data.entity.StationEntity
import org.ort.data.entity.TransmissionEntity

/**
 * Read-only aggregate queries behind build-plan P17's station and frequency views (FR-UI-9,
 * FR-UI-10) — a new DAO file, deliberately not an edit to [TransmissionDao] or [CatalogDao],
 * both of which a concurrent session (P15, adding search queries) touches at the same time; see
 * build-plan P17's "Owns" note.
 *
 * No schema change and no new entity: every query here reads columns [TransmissionEntity] and
 * [StationEntity] already declare (build-plan P5). `TransmissionEntity.frequencyHz` is nullable
 * and unpopulated until the rig module (M7) writes it — [listDistinctFrequencies] excludes null
 * rather than inventing an "unknown frequency" bucket for it.
 */
@Dao
public interface ActivityDao {

    /** Every transmission ever attributed to [stationId], across every session (FR-UI-9), oldest first. */
    @Query("SELECT * FROM transmission WHERE stationId = :stationId ORDER BY startedAtUtc")
    public suspend fun transmissionsForStation(stationId: String): List<TransmissionEntity>

    /** Every transmission ever heard on [frequencyHz], across every session (FR-UI-10), oldest first. */
    @Query("SELECT * FROM transmission WHERE frequencyHz = :frequencyHz ORDER BY startedAtUtc")
    public suspend fun transmissionsForFrequency(frequencyHz: Long): List<TransmissionEntity>

    /** Every station ever heard, most recently heard first — backs the "Stations" list (FR-UI-9). */
    @Query("SELECT * FROM station ORDER BY lastHeardAt DESC")
    public suspend fun listStations(): List<StationEntity>

    /** Every distinct, real frequency ever recorded on a transmission — backs the "Frequencies" list (FR-UI-10). */
    @Query("SELECT DISTINCT frequencyHz FROM transmission WHERE frequencyHz IS NOT NULL ORDER BY frequencyHz")
    public suspend fun listDistinctFrequencies(): List<Long>
}
