package com.example.letterland;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface LogDao {
    @Insert
    void insertLog(LogEntry log);

    @Query("SELECT * FROM log_table ORDER BY timestamp DESC")
    List<LogEntry> getAllLogs();

    @Delete
    void deleteLog(LogEntry log);

    // 🌟 FIX Query 1: Safely updates the name only if it exists at the absolute end of the details string
    @Query("UPDATE log_table SET details = substr(details, 1, length(details) - length(:oldName)) || :newName WHERE details LIKE '%|' || :oldName")
    void updateTrailingProfileName(String oldName, String newName);

    // 🌟 FIX Query 2: Safely updates the name only if it is bounded on both sides by pipes in the middle of a string
    @Query("UPDATE log_table SET details = REPLACE(details, '|' || :oldName || '|', '|' || :newName || '|') WHERE details LIKE '%|' || :oldName || '|%'")
    void updateMiddleProfileName(String oldName, String newName);

    // Coordinates both queries cleanly without breaking your existing ProfilesActivity method calls
    default void updateProfileNameInLogs(String oldName, String newName) {
        updateTrailingProfileName(oldName, newName);
        updateMiddleProfileName(oldName, newName);
    }

    // --- USER LOG QUERY ---
    @Query("SELECT * FROM log_table WHERE action IN ('ADDED WORD', 'EDITED WORD', 'ADMIN ADDED WORD') ORDER BY timestamp DESC")
    List<LogEntry> getHistoryLogs();

    // --- MANUAL PURGE QUERIES ---
    @Query("DELETE FROM log_table WHERE action IN ('ADDED WORD', 'EDITED WORD', 'ADMIN ADDED WORD')")
    void deleteHistoryLogs();

    @Query("DELETE FROM log_table WHERE action NOT IN ('ADDED WORD', 'EDITED WORD', 'ADMIN ADDED WORD', 'DELETED WORD')")
    void deletePlayerActivityLogs();

    @Query("DELETE FROM log_table WHERE action = 'DELETED WORD'")
    void deleteDeletedItemLogs();

    // --- AUTOMATIC CONVEYOR BELT QUERIES ---

    // Auto-deletes the oldest History Logs (Keeps newest X)
    @Query("DELETE FROM log_table WHERE action IN ('ADDED WORD', 'EDITED WORD', 'ADMIN ADDED WORD') AND id NOT IN (SELECT id FROM log_table WHERE action IN ('ADDED WORD', 'EDITED WORD', 'ADMIN ADDED WORD') ORDER BY timestamp DESC LIMIT :limit)")
    void autoPruneHistoryLogs(int limit);

    // Auto-deletes the oldest User Logs (Keeps newest X)
    @Query("DELETE FROM log_table WHERE action NOT IN ('ADDED WORD', 'EDITED WORD', 'ADMIN ADDED WORD', 'DELETED WORD') AND id NOT IN (SELECT id FROM log_table WHERE action NOT IN ('ADDED WORD', 'EDITED WORD', 'ADMIN ADDED WORD', 'DELETED WORD') ORDER BY timestamp DESC LIMIT :limit)")
    void autoPrunePlayerActivityLogs(int limit);

    // Auto-deletes the oldest Deleted Items (Keeps newest X)
    @Query("DELETE FROM log_table WHERE action = 'DELETED WORD' AND id NOT IN (SELECT id FROM log_table WHERE action = 'DELETED WORD' ORDER BY timestamp DESC LIMIT :limit)")
    void autoPruneDeletedItemLogs(int limit);
}