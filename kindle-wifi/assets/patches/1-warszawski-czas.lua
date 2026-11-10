-- Use Warsaw time in KOReader, with the current EU daylight-saving rules.
-- Runs at startup. Changes this process's timezone, not the system clock.
local ffi = require("ffi")

ffi.cdef[[
    int setenv(const char *name, const char *value, int overwrite);
    void tzset(void);
]]

if ffi.C.setenv("TZ", "CET-1CEST,M3.5.0/2,M10.5.0/3", 1) ~= 0 then
    error("Could not set Warsaw time in KOReader")
end

ffi.C.tzset()
