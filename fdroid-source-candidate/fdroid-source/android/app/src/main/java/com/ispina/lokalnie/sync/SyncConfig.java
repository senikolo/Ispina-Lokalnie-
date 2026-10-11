package com.ispina.lokalnie.sync;

public final class SyncConfig {
    private SyncConfig() {}

    /**
     * Oficjalne źródła statycznego GTFS MLD. Worker próbuje je po kolei i
     * akceptuje wyłącznie archiwum przechodzące walidację A4/A7/A65.
     */
    public static final String[] MLD_GTFS_URLS = new String[]{
            "https://files.kolejemalopolskie.com.pl/mld-gtfs.zip",
            "https://files.kolejemalopolskie.com.pl/GTFS.zip"
    };

    /**
     * Stały adres wdrożonego Google Apps Script Web App dla linii gminnej.
     */
    public static final String DEFAULT_GMINNA_API_URL = "https://script.google.com/macros/s/AKfycbyup_J3I1nTy-u1LMQC2RPSVuEvU5EN0juIVagZtp4ZMtKx7VjdErl1c7Mdlqt0x_C38w/exec";

    public static final String PREFS = "schedule_sync";
    public static final String PREF_GMINNA_URL = "gminna_api_url";
    public static final String FILE_MLD = "mld_schedule.json";
    public static final String FILE_GMINNA = "gminna_schedule.json";
}
